package com.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Serializable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class TextViewerActivity extends Activity {

    private static final String TAG = "TextViewerActivity";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String RESULT_FILE_DELETED = "file_deleted";
    private static final int LINES_PER_PAGE = 300;

    private TextView fileNameTextView;
    private TextView contentTextView;
    private TextView pageNumberTextView;
    private Button prevButton;
    private Button nextButton;
    private ImageButton closeButton;
    private ImageButton copyButton;
    private ImageButton deleteButton;
    private ImageButton shareButton;
    private ScrollView contentScrollView;

    private ImageButton openWithButton;

    private RelativeLayout deletionProgressLayout;
    private ProgressBar deletionProgressBar;
    private TextView deletionProgressText;

    private List<String> pages = new ArrayList<>();
    private int currentPageIndex = 0;
    private String filePath;
    private BroadcastReceiver deleteCompletionReceiver;
    private BroadcastReceiver compressionBroadcastReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_viewer);

        fileNameTextView = findViewById(R.id.file_name_text_viewer);
        contentTextView = findViewById(R.id.content_text_view);
        pageNumberTextView = findViewById(R.id.page_number_text);
        prevButton = findViewById(R.id.prev_page_button);
        nextButton = findViewById(R.id.next_page_button);
        closeButton = findViewById(R.id.close_button_text_viewer);
        copyButton = findViewById(R.id.copy_button_viewer);
        deleteButton = findViewById(R.id.delete_button_viewer);
        shareButton = findViewById(R.id.share_button_viewer);
        contentScrollView = findViewById(R.id.content_scroll_view);
        openWithButton = findViewById(R.id.open_with_button_text);
        deletionProgressLayout = findViewById(R.id.deletion_progress_layout);
        deletionProgressBar = findViewById(R.id.deletion_progress_bar);
        deletionProgressText = findViewById(R.id.deletion_progress_text);


        filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (filePath != null) {
            File file = new File(filePath);
            fileNameTextView.setText(file.getName());
            new LoadFileTask().execute(filePath);
        } else {
            Toast.makeText(this, "Error: No file path provided.", Toast.LENGTH_LONG).show();
            finish();
        }

        setupListeners();
        setupBroadcastReceivers();
    }

    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					finish();
				}
			});

        copyButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					copyAllContent();
				}
			});


        deleteButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showFileActionDialog();
				}
			});

        shareButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					shareFile();
				}
			});

        openWithButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					openWith();
				}
			});

        prevButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (currentPageIndex > 0) {
						currentPageIndex--;
						showPage();
					}
				}
			});

        nextButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (currentPageIndex < pages.size() - 1) {
						currentPageIndex++;
						showPage();
					}
				}
			});
    }

    private void copyAllContent() {
        if (filePath == null) {
            Toast.makeText(this, "Error: File path is missing.", Toast.LENGTH_SHORT).show();
            return;
        }

        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) {
            Toast.makeText(this, "Cannot read file for copying.", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder fullContent = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                fullContent.append(line).append("\n");
            }
            reader.close();

            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("File Content", fullContent.toString());
                clipboard.setPrimaryClip(clip);
                Toast.makeText(TextViewerActivity.this, "Entire file content copied to clipboard.", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading file for copy operation", e);
            Toast.makeText(this, "Failed to read file for copying.", Toast.LENGTH_SHORT).show();
        }
    }


    private void shareFile() {
        if (filePath == null) {
            Toast.makeText(this, "Error: File path is missing.", Toast.LENGTH_SHORT).show();
            return;
        }

        File fileToShare = new File(filePath);
        if (!fileToShare.exists()) {
            Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Uri fileUri = FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".provider", fileToShare);

            Intent shareIntent = new Intent();
            shareIntent.setAction(Intent.ACTION_SEND);
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.setType("text/plain");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "Share file via"));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "File Provider error: " + e.getMessage());
            Toast.makeText(this, "Error: Could not generate a sharable link for this file.", Toast.LENGTH_LONG).show();
        }
    }

    private void openWith() {
        if (filePath == null) {
            Toast.makeText(this, "Error: File path is missing.", Toast.LENGTH_SHORT).show();
            return;
        }
        File fileToOpen = new File(filePath);
        if (!fileToOpen.exists()) {
            Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri fileUri = FileProvider.getUriForFile(this, getApplicationContext().getPackageName() + ".provider", fileToOpen);
            Intent openIntent = new Intent(Intent.ACTION_VIEW);
            openIntent.setDataAndType(fileUri, getMimeType(fileToOpen.getAbsolutePath()));
            openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(openIntent, "Open with"));
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "Error: Could not generate a link to open this file.", Toast.LENGTH_LONG).show();
        }
    }

    private String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return type;
    }


    private void showFileActionDialog() {
        final CharSequence[] options = {"Details", "Send to Drop Zone", "Compress", "Hide", "Move to Recycle Bin", "Delete Permanently"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Choose an action");
        builder.setItems(options, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					switch (which) {
						case 0: // Details
							showDetailsDialog();
							break;
                        case 1: // Send to Drop Zone
                            showSendToDropDialog(new File(filePath));
                            break;
						case 2: // Compress
							compressFile();
							break;
						case 3: // Hide
							hideFile();
							break;
						case 4: // Move to Recycle
							moveToRecycleBin();
							break;
						case 5: // Delete
							performFileDeletion();
							break;
					}
				}
			});
        builder.show();
    }

    private void showDetailsDialog() {
        final List<File> files = new ArrayList<>();
        files.add(new File(filePath));

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_details, null);
        builder.setView(dialogView);
        builder.setCancelable(false);

        final TextView basicDetailsText = dialogView.findViewById(R.id.details_text_basic);
        final TextView aiDetailsText = dialogView.findViewById(R.id.details_text_ai);
        final ProgressBar progressBar = dialogView.findViewById(R.id.details_progress_bar);
        final Button moreButton = dialogView.findViewById(R.id.details_button_more);
        final Button copyButton = dialogView.findViewById(R.id.details_button_copy);
        final Button closeButton = dialogView.findViewById(R.id.details_button_close);

        final AlertDialog dialog = builder.create();

        File file = files.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(file.getName()).append("\n");
        sb.append("Path: ").append(file.getAbsolutePath()).append("\n");
        sb.append("Size: ").append(Formatter.formatFileSize(this, file.length())).append("\n");
        sb.append("Last Modified: ").append(new Date(file.lastModified()).toString());
        basicDetailsText.setText(sb.toString());

        final GeminiAnalyzer analyzer = new GeminiAnalyzer(this, aiDetailsText, progressBar, copyButton);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        moreButton.setEnabled(ApiKeyManager.getApiKey(this) != null && isConnected);

        moreButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					analyzer.analyze(files);
				}
			});

        copyButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
					ClipData clip = ClipData.newPlainText("AI Summary", aiDetailsText.getText());
					clipboard.setPrimaryClip(clip);
					Toast.makeText(TextViewerActivity.this, "Summary copied to clipboard.", Toast.LENGTH_SHORT).show();
				}
			});

        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});

        dialog.show();
    }

    private void showSendToDropDialog(final File fileToSend) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_send_drop, null);
        final EditText receiverUsernameInput = dialogView.findViewById(R.id.edit_text_receiver_username);

        builder.setView(dialogView)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        String receiverUsername = receiverUsernameInput.getText().toString().trim();
                        if (receiverUsername.isEmpty()) {
                            Toast.makeText(TextViewerActivity.this, "Receiver username cannot be empty.", Toast.LENGTH_SHORT).show();
                        } else {
                            showSenderWarningDialog(receiverUsername, fileToSend);
                        }
                    }
                })
                .setNegativeButton("Cancel", null);
        builder.create().show();
    }
    
    private void showSenderWarningDialog(final String receiverUsername, final File fileToSend) {
        final String secretNumber = generateSecretNumber();

        new AlertDialog.Builder(this)
            .setTitle("Important: Connection Stability")
            .setMessage("You are about to act as a temporary server for this file transfer.\n\n"
                    + "Please keep the app open and maintain a stable internet connection until the transfer is complete.\n\n"
                    + "Your Secret Number for this transfer is:\n" + secretNumber + "\n\nShare this number with the receiver.")
            .setPositiveButton("I Understand, Start Sending", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startSenderService(receiverUsername, secretNumber, fileToSend);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void startSenderService(String receiverUsername, String secretNumber, File fileToSend) {
        if (fileToSend == null || !fileToSend.exists()) {
            Toast.makeText(this, "Error: File to send does not exist.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, SenderService.class);
        intent.setAction(SenderService.ACTION_START_SEND);
        intent.putExtra(SenderService.EXTRA_FILE_PATH, fileToSend.getAbsolutePath());
        intent.putExtra(SenderService.EXTRA_RECEIVER_USERNAME, receiverUsername);
        intent.putExtra(SenderService.EXTRA_SECRET_NUMBER, secretNumber);
        ContextCompat.startForegroundService(this, intent);
    }
    
    private String generateSecretNumber() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    private void compressFile() {
        File currentFile = new File(filePath);
        File parentDir = currentFile.getParentFile();
        if (parentDir != null) {
            List<File> filesToCompress = new ArrayList<>();
            filesToCompress.add(currentFile);
            ArchiveUtils.startCompression(this, filesToCompress, parentDir);
            Toast.makeText(this, "Compression started in background.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Cannot determine destination for archive.", Toast.LENGTH_SHORT).show();
        }
    }

    private void hideFile() {
        if (filePath == null) {
            Toast.makeText(this, "Error: File path is missing.", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<File> filesToHide = new ArrayList<>();
        filesToHide.add(new File(filePath));

        Intent intent = new Intent(this, FileHiderActivity.class);
        intent.putExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE, (Serializable) filesToHide);
        startActivity(intent);

        Intent resultIntent = new Intent();
        resultIntent.putExtra(RESULT_FILE_DELETED, true);
        setResult(Activity.RESULT_OK, resultIntent);
        finish();
    }

    private void moveToRecycleBin() {
        if (filePath == null) {
            Toast.makeText(this, "Error: File path is missing.", Toast.LENGTH_SHORT).show();
            return;
        }
        File sourceFile = new File(filePath);
        if (!sourceFile.exists()) {
            Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        File recycleBinDir = new File(Environment.getExternalStorageDirectory(), "HFMRecycleBin");
        if (!recycleBinDir.exists()) {
            if (!recycleBinDir.mkdir()) {
                Toast.makeText(this, "Failed to create Recycle Bin folder.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        File destFile = new File(recycleBinDir, sourceFile.getName());
        if (destFile.exists()) {
            String name = sourceFile.getName();
            String extension = "";
            int dotIndex = name.lastIndexOf(".");
            if (dotIndex > 0) {
                extension = name.substring(dotIndex);
                name = name.substring(0, dotIndex);
            }
            destFile = new File(recycleBinDir, name + "_" + System.currentTimeMillis() + extension);
        }

        if (sourceFile.renameTo(destFile)) {
            Toast.makeText(this, "File moved to Recycle Bin.", Toast.LENGTH_SHORT).show();
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(sourceFile)));
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destFile)));
            Intent resultIntent = new Intent();
            resultIntent.putExtra(RESULT_FILE_DELETED, true);
            setResult(Activity.RESULT_OK, resultIntent);
            finish();
        } else {
            Toast.makeText(this, "Failed to move file.", Toast.LENGTH_SHORT).show();
        }
    }

    private void performFileDeletion() {
        if (filePath == null) {
            Toast.makeText(this, "Error: File path is missing.", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<String> filesToDelete = new ArrayList<>();
        filesToDelete.add(filePath);

        findViewById(R.id.footer_buttons_layout).setVisibility(View.GONE);
        deletionProgressLayout.setVisibility(View.VISIBLE);
        deletionProgressBar.setIndeterminate(true);
        deletionProgressText.setText("Deleting...");

        Intent intent = new Intent(this, DeleteService.class);
        intent.putStringArrayListExtra(DeleteService.EXTRA_FILES_TO_DELETE, filesToDelete);
        ContextCompat.startForegroundService(this, intent);
    }

    private void showPage() {
        if (pages.isEmpty() || currentPageIndex < 0 || currentPageIndex >= pages.size()) {
            return;
        }
        contentTextView.setText(pages.get(currentPageIndex));
        contentScrollView.scrollTo(0, 0);
        updateUi();
    }

    private void updateUi() {
        int totalPages = pages.size();
        if (totalPages == 0) {
            pageNumberTextView.setText("Page 0 / 0");
        } else {
            pageNumberTextView.setText("Page " + (currentPageIndex + 1) + " / " + totalPages);
        }
        prevButton.setEnabled(currentPageIndex > 0);
        nextButton.setEnabled(currentPageIndex < totalPages - 1);
    }

    private class LoadFileTask extends AsyncTask<String, Void, Boolean> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            contentTextView.setText("Loading file...");
        }

        @Override
        protected Boolean doInBackground(String... params) {
            String filePath = params[0];
            File file = new File(filePath);
            if (!file.exists() || !file.canRead()) {
                return false;
            }

            try {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line;
                StringBuilder pageBuilder = new StringBuilder();
                int lineCount = 0;

                while ((line = reader.readLine()) != null) {
                    pageBuilder.append(line).append("\n");
                    lineCount++;
                    if (lineCount >= LINES_PER_PAGE) {
                        pages.add(pageBuilder.toString());
                        pageBuilder = new StringBuilder();
                        lineCount = 0;
                    }
                }
                if (pageBuilder.length() > 0) {
                    pages.add(pageBuilder.toString());
                }
                reader.close();
                return true;

            } catch (IOException e) {
                Log.e(TAG, "Error reading file", e);
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            if (success) {
                if (pages.isEmpty()) {
                    contentTextView.setText("(This file is empty)");
                    pages.add("(This file is empty)");
                }
                currentPageIndex = 0;
                showPage();
            } else {
                Toast.makeText(TextViewerActivity.this, "Failed to load file.", Toast.LENGTH_LONG).show();
                contentTextView.setText("Could not read this file.");
            }
        }
    }

    private void setupBroadcastReceivers() {
        deleteCompletionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                deletionProgressLayout.setVisibility(View.GONE);
                findViewById(R.id.footer_buttons_layout).setVisibility(View.VISIBLE);
                int deletedCount = intent.getIntExtra(DeleteService.EXTRA_DELETED_COUNT, 0);
                if (deletedCount > 0) {
                    Toast.makeText(TextViewerActivity.this, "File deleted successfully.", Toast.LENGTH_SHORT).show();
                    Intent resultIntent = new Intent();
                    resultIntent.putExtra(RESULT_FILE_DELETED, true);
                    TextViewerActivity.this.setResult(Activity.RESULT_OK, resultIntent);
                    finish();
                } else {
                    Toast.makeText(TextViewerActivity.this, "Failed to delete the file.", Toast.LENGTH_SHORT).show();
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(deleteCompletionReceiver, new IntentFilter(DeleteService.ACTION_DELETE_COMPLETE));

        compressionBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Do nothing specific on compression complete in this screen
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(compressionBroadcastReceiver, new IntentFilter(CompressionService.ACTION_COMPRESSION_COMPLETE));
    }

    @Override
    protected void onDestroy() {
        if (deleteCompletionReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(deleteCompletionReceiver);
        }
        if (compressionBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(compressionBroadcastReceiver);
        }
        super.onDestroy();
    }
}