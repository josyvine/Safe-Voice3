package com.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HFMDropActivity extends Activity {

    private static final String TAG = "HFMDropActivity";

    // UI Elements
    private ImageButton backButton;
    private TextView usernameTextView;
    private Button regenerateIdButton;
    private RecyclerView requestsRecyclerView;
    private ProgressBar loadingRequestsProgress;
    private TextView emptyViewRequests;

    // Firebase
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseUser currentUser;
    private ListenerRegistration dropRequestListener;

    // RecyclerView
    private DropRequestAdapter adapter;
    private List<DropRequest> requestList;

    private static final String[] ADJECTIVES = {"Red", "Blue", "Green", "Silent", "Fast", "Brave", "Ancient", "Wandering", "Golden", "Iron"};
    private static final String[] NOUNS = {"Tiger", "Lion", "Eagle", "Fox", "Wolf", "River", "Mountain", "Star", "Comet", "Shadow"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hfm_drop);

        initializeViews();
        initializeFirebase();
        setupRecyclerView();
        setupListeners();
    }

    @Override
    protected void onStart() {
        super.onStart();
        checkCurrentUser();
    }

    @Override
    protected void onStop() {
        super.onStop();
        removeListener();
    }

    private void initializeViews() {
        backButton = findViewById(R.id.back_button_hfm_drop);
        usernameTextView = findViewById(R.id.username_text_view);
        regenerateIdButton = findViewById(R.id.regenerate_id_button);
        requestsRecyclerView = findViewById(R.id.requests_recycler_view);
        loadingRequestsProgress = findViewById(R.id.loading_requests_progress);
        emptyViewRequests = findViewById(R.id.empty_view_requests);
    }

    private void initializeFirebase() {
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
    }

    private void setupRecyclerView() {
        requestList = new ArrayList<>();
        adapter = new DropRequestAdapter(this, requestList, new DropRequestAdapter.OnRequestInteractionListener() {
            @Override
            public void onAccept(DropRequest request) {
                handleAccept(request);
            }

            @Override
            public void onDecline(DropRequest request) {
                handleDecline(request);
            }
        });
        requestsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        requestsRecyclerView.setAdapter(adapter);
    }

    private void setupListeners() {
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        regenerateIdButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(HFMDropActivity.this)
                        .setTitle("Regenerate ID")
                        .setMessage("Are you sure? This will permanently delete your current ID and any pending requests. This action cannot be undone.")
                        .setPositiveButton("Regenerate", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                regenerateIdentity();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }

    private void checkCurrentUser() {
        currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            signInAnonymously();
        } else {
            updateUiWithUser(currentUser);
        }
    }

    private void signInAnonymously() {
        usernameTextView.setText("Generating ID...");
        regenerateIdButton.setEnabled(false);
        mAuth.signInAnonymously().addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
            @Override
            public void onComplete(@NonNull Task<AuthResult> task) {
                if (task.isSuccessful()) {
                    Log.d(TAG, "signInAnonymously:success");
                    currentUser = mAuth.getCurrentUser();
                    updateUiWithUser(currentUser);
                } else {
                    Log.w(TAG, "signInAnonymously:failure", task.getException());
                    Toast.makeText(HFMDropActivity.this, "Authentication failed.", Toast.LENGTH_SHORT).show();
                    usernameTextView.setText("Authentication Failed");
                }
            }
        });
    }

    private void regenerateIdentity() {
        if (currentUser != null) {
            usernameTextView.setText("Regenerating...");
            regenerateIdButton.setEnabled(false);
            removeListener();
            currentUser.delete().addOnCompleteListener(new OnCompleteListener<Void>() {
                @Override
                public void onComplete(@NonNull Task<Void> task) {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "User account deleted.");
                        signInAnonymously();
                    } else {
                        Log.w(TAG, "User account deletion failed.", task.getException());
                        Toast.makeText(HFMDropActivity.this, "Failed to regenerate ID.", Toast.LENGTH_SHORT).show();
                        // Re-enable UI with old user if deletion fails
                        updateUiWithUser(currentUser);
                    }
                }
            });
        }
    }

    private void updateUiWithUser(FirebaseUser user) {
        if (user != null) {
            String username = generateUsernameFromUid(user.getUid());
            usernameTextView.setText(username);
            regenerateIdButton.setEnabled(true);
            listenForDropRequests(username);
        }
    }

    private String generateUsernameFromUid(String uid) {
        long hash = uid.hashCode();
        int adjIndex = (int) (Math.abs(hash % ADJECTIVES.length));
        int nounIndex = (int) (Math.abs((hash / ADJECTIVES.length) % NOUNS.length));
        int number = (int) (Math.abs((hash / (ADJECTIVES.length * NOUNS.length)) % 100));
        return ADJECTIVES[adjIndex] + "-" + NOUNS[nounIndex] + "-" + number;
    }

    private void listenForDropRequests(String username) {
        removeListener();
        loadingRequestsProgress.setVisibility(View.VISIBLE);
        requestsRecyclerView.setVisibility(View.GONE);
        emptyViewRequests.setVisibility(View.GONE);

        Query query = db.collection("drop_requests")
                .whereEqualTo("receiverUsername", username)
                .whereEqualTo("status", "pending");

        dropRequestListener = query.addSnapshotListener(new EventListener<QuerySnapshot>() {
            @Override
            public void onEvent(QuerySnapshot snapshots, FirebaseFirestoreException e) {
                loadingRequestsProgress.setVisibility(View.GONE);
                if (e != null) {
                    Log.w(TAG, "Listen failed.", e);
                    Toast.makeText(HFMDropActivity.this, "Error listening for requests.", Toast.LENGTH_SHORT).show();
                    return;
                }

                for (DocumentChange dc : snapshots.getDocumentChanges()) {
                    if (dc.getType() == DocumentChange.Type.ADDED) {
                        DropRequest request = dc.getDocument().toObject(DropRequest.class);
                        request.id = dc.getDocument().getId();
                        requestList.add(request);
                    }
                }

                if (requestList.isEmpty()) {
                    emptyViewRequests.setVisibility(View.VISIBLE);
                    requestsRecyclerView.setVisibility(View.GONE);
                } else {
                    emptyViewRequests.setVisibility(View.GONE);
                    requestsRecyclerView.setVisibility(View.VISIBLE);
                }
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void removeListener() {
        if (dropRequestListener != null) {
            dropRequestListener.remove();
            dropRequestListener = null;
        }
    }

    private void handleAccept(final DropRequest request) {
        if (currentUser == null) {
            Toast.makeText(this, "Authentication error. Please restart the app.", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // --- THIS IS THE UPDATE ---
        // Add the receiver's ID to the document to satisfy the new security rules.
        Map<String, Object> updates = new HashMap<>();
        updates.put("status", "accepted");
        updates.put("receiverId", currentUser.getUid());

        db.collection("drop_requests").document(request.id).update(updates)
            .addOnSuccessListener(new OnSuccessListener<Void>() {
                @Override
                public void onSuccess(Void aVoid) {
                    Log.d(TAG, "Drop request accepted. Starting download service.");
                    Intent intent = new Intent(HFMDropActivity.this, DownloadService.class);
                    intent.putExtra("drop_request_id", request.id);
                    intent.putExtra("sender_id", request.senderId);
                    intent.putExtra("filename", request.filename);
                    intent.putExtra("filesize", request.filesize);
                    ContextCompat.startForegroundService(HFMDropActivity.this, intent);
                }
            })
            .addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(@NonNull Exception e) {
                    Log.e(TAG, "Failed to accept drop request", e);
                    Toast.makeText(HFMDropActivity.this, "Failed to accept request: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });


        // Remove from list and update UI immediately
        requestList.remove(request);
        adapter.notifyDataSetChanged();
        if (requestList.isEmpty()) {
            emptyViewRequests.setVisibility(View.VISIBLE);
        }
    }

    private void handleDecline(DropRequest request) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("status", "declined");
        db.collection("drop_requests").document(request.id).update(updates);

        // Remove from list and update UI immediately
        requestList.remove(request);
        adapter.notifyDataSetChanged();
        if (requestList.isEmpty()) {
            emptyViewRequests.setVisibility(View.VISIBLE);
        }
    }

    // --- Data Model and Adapter ---

    public static class DropRequest {
        public String id; // Firestore document ID
        public String senderId;
        public String senderUsername;
        public String receiverUsername;
        public String filename;
        public long filesize;
        public String status;
        public String receiverId; // Added for security rules

        public DropRequest() {} // Needed for Firestore
    }

    public static class DropRequestAdapter extends RecyclerView.Adapter<DropRequestAdapter.ViewHolder> {
        private Context context;
        private List<DropRequest> requestList;
        private OnRequestInteractionListener listener;

        public interface OnRequestInteractionListener {
            void onAccept(DropRequest request);
            void onDecline(DropRequest request);
        }

        public DropRequestAdapter(Context context, List<DropRequest> requestList, OnRequestInteractionListener listener) {
            this.context = context;
            this.requestList = requestList;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.list_item_drop_request, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final DropRequest request = requestList.get(position);

            holder.filename.setText(request.filename);
            holder.senderInfo.setText("From: " + request.senderUsername);
            holder.filesize.setText("Size: " + Formatter.formatFileSize(context, request.filesize));

            holder.acceptButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onAccept(request);
                    }
                }
            });

            holder.declineButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) {
                        listener.onDecline(request);
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return requestList.size();
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            TextView filename, senderInfo, filesize;
            Button acceptButton, declineButton;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                filename = itemView.findViewById(R.id.drop_request_filename);
                senderInfo = itemView.findViewById(R.id.drop_request_sender_info);
                filesize = itemView.findViewById(R.id.drop_request_filesize);
                acceptButton = itemView.findViewById(R.id.button_accept_drop);
                declineButton = itemView.findViewById(R.id.button_decline_drop);
            }
        }
    }
}