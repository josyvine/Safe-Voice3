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
import android.text.Html;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileDeleteActivity extends Activity {

    private TextView titleTextView;
    private ImageButton backButton;
    private RecyclerView fileGrid;
    private Button selectAllButton;
    private TextView selectionCountText;
    private ImageButton deleteButton;

    private RelativeLayout deletionProgressLayout;
    private ProgressBar deletionProgressBar;
    private TextView deletionProgressText;

    private FileDeleteAdapter adapter;
    private ArrayList<File> fileList;
    private boolean isAllSelected = false;

    private static final int CATEGORY_IMAGES = 1;
    private static final int CATEGORY_VIDEOS = 2;
    private static final int CATEGORY_AUDIO = 3;
    private static final int CATEGORY_DOCS = 4;
    private static final int CATEGORY_SCRIPTS = 5;
    private static final int CATEGORY_OTHER = 6;

    private static final Pattern FILE_BASE_NAME_PATTERN = Pattern.compile("^(IMG|VID|PANO|DSC)_\\d{8}_\\d{6}");

    private BroadcastReceiver deleteCompletionReceiver;
    private BroadcastReceiver compressionBroadcastReceiver;

    private List<File> mFilesPendingPermission;
    private Runnable mPendingOperation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_delete);

        initializeViews();

        Intent intent = getIntent();
        String folderName = intent.getStringExtra(FolderListActivity.EXTRA_FOLDER_NAME);
        Serializable fileListSerializable = intent.getSerializableExtra(FolderListActivity.EXTRA_FILE_LIST);

        if (folderName == null || !(fileListSerializable instanceof ArrayList)) {
            Toast.makeText(this, "Error: Invalid file data received.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        fileList = (ArrayList<File>) fileListSerializable;
        titleTextView.setText(folderName);

        setupRecyclerView();
        setupListeners();
        setupBroadcastReceivers();
        updateSelectionCount();
    }

    private void initializeViews() {
        titleTextView = findViewById(R.id.title_file_delete);
        backButton = findViewById(R.id.back_button_file_delete);
        fileGrid = findViewById(R.id.file_delete_grid);
        selectAllButton = findViewById(R.id.select_all_button);
        selectionCountText = findViewById(R.id.selection_count_text);
        deleteButton = findViewById(R.id.delete_button_final);

        deletionProgressLayout = findViewById(R.id.deletion_progress_layout);
        deletionProgressBar = findViewById(R.id.deletion_progress_bar);
        deletionProgressText = findViewById(R.id.deletion_progress_text);
    }

    private void setupRecyclerView() {
        adapter = new FileDeleteAdapter(this, fileList, new FileDeleteAdapter.OnItemClickListener() {
				@Override
				public void onItemClick(int position) {
					FileDeleteAdapter.FileItem item = adapter.getItems().get(position);
					item.setSelected(!item.isSelected());
					adapter.notifyItemChanged(position);
					updateSelectionCount();
				}

				@Override
				public void onItemLongClick(int position) {
					final File selectedFile = adapter.getItems().get(position).getFile();
					new AlertDialog.Builder(FileDeleteActivity.this)
                        .setItems(new CharSequence[]{"Open", "Details"}, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) {
                                    openFileViewer(selectedFile);
                                } else if (which == 1) {
                                    ArrayList<File> files = new ArrayList<>();
                                    files.add(selectedFile);
                                    showDetailsDialog(files);
                                }
                            }
                        })
                        .show();
				}

				@Override
				public void onSelectionChanged() {
					updateSelectionCount();
				}
			});
        fileGrid.setLayoutManager(new GridLayoutManager(this, 3));
        fileGrid.setAdapter(adapter);
    }

    private void setupListeners() {
        backButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					finish();
				}
			});

        selectAllButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					isAllSelected = !isAllSelected;
					adapter.selectAll(isAllSelected);
					if (isAllSelected) {
						selectAllButton.setText("Deselect All");
					} else {
						selectAllButton.setText("Select All");
					}
				}
			});

        deleteButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showFileOperationsDialog();
				}
			});
    }

    private void updateSelectionCount() {
        int count = 0;
        for (FileDeleteAdapter.FileItem item : adapter.getItems()) {
            if (item.isSelected()) {
                count++;
            }
        }
        selectionCountText.setText(count + " files selected");

        if (count == adapter.getItemCount() && count > 0) {
            isAllSelected = true;
            selectAllButton.setText("Deselect All");
        } else {
            isAllSelected = false;
            selectAllButton.setText("Select All");
        }
    }

    private List<File> getSelectedFiles() {
        List<File> selectedFiles = new ArrayList<>();
        for (FileDeleteAdapter.FileItem item : adapter.getItems()) {
            if (item.isSelected()) {
                selectedFiles.add(item.getFile());
            }
        }
        return selectedFiles;
    }

    private void showFileOperationsDialog() {
        final List<File> selectedFiles = getSelectedFiles();
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_file_operations, null);
        builder.setView(dialogView);

        final AlertDialog dialog = builder.create();

        Button detailsButton = dialogView.findViewById(R.id.button_details);
        Button sendToDropZoneButton = dialogView.findViewById(R.id.button_send_to_drop_zone);
        Button compressButton = dialogView.findViewById(R.id.button_compress);
        Button copyButton = dialogView.findViewById(R.id.button_copy);
        Button moveButton = dialogView.findViewById(R.id.button_move);
        Button hideButton = dialogView.findViewById(R.id.button_hide);
        Button deleteButton = dialogView.findViewById(R.id.button_delete_permanently);
        Button recycleButton = dialogView.findViewById(R.id.button_move_to_recycle);

        detailsButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showDetailsDialog(selectedFiles);
					dialog.dismiss();
				}
			});
        
        sendToDropZoneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedFiles.size() == 1) {
                    showSendToDropDialog(selectedFiles.get(0));
                } else {
                    Toast.makeText(FileDeleteActivity.this, "HFM Drop currently supports sending a single file at a time.", Toast.LENGTH_LONG).show();
                }
                dialog.dismiss();
            }
        });

        compressButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					File parentDir = selectedFiles.get(0).getParentFile();
					if (parentDir != null) {
						ArchiveUtils.startCompression(FileDeleteActivity.this, selectedFiles, parentDir);
						Toast.makeText(FileDeleteActivity.this, "Compression started in background.", Toast.LENGTH_SHORT).show();
					} else {
						Toast.makeText(FileDeleteActivity.this, "Cannot determine destination for archive.", Toast.LENGTH_SHORT).show();
					}
					dialog.dismiss();
				}
			});

        copyButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					com.hfm.app.ClipboardManager.getInstance().setItems(selectedFiles, com.hfm.app.ClipboardManager.Operation.COPY);
					Toast.makeText(FileDeleteActivity.this, selectedFiles.size() + " item(s) ready to copy. Navigate to a folder to paste.", Toast.LENGTH_LONG).show();
					dialog.dismiss();
				}
			});

        moveButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					com.hfm.app.ClipboardManager.getInstance().setItems(selectedFiles, com.hfm.app.ClipboardManager.Operation.MOVE);
					Toast.makeText(FileDeleteActivity.this, selectedFiles.size() + " item(s) ready to move. Navigate to a folder to paste.", Toast.LENGTH_LONG).show();
					dialog.dismiss();
				}
			});

        hideButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					Intent intent = new Intent(FileDeleteActivity.this, FileHiderActivity.class);
					intent.putExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE, (Serializable) selectedFiles);
					startActivity(intent);
					dialog.dismiss();
				}
			});

        deleteButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					initiateDeletionProcess();
					dialog.dismiss();
				}
			});

        recycleButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					moveToRecycleBin(selectedFiles);
					dialog.dismiss();
				}
			});

        dialog.show();
    }

    private void showDetailsDialog(final List<File> files) {
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

        if (files.size() == 1) {
            File file = files.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("Name: ").append(file.getName()).append("\n");
            sb.append("Path: ").append(file.getAbsolutePath()).append("\n");
            sb.append("Size: ").append(Formatter.formatFileSize(this, file.length())).append("\n");
            sb.append("Last Modified: ").append(new Date(file.lastModified()).toString());
            basicDetailsText.setText(sb.toString());
        } else {
            long totalSize = 0;
            for (File file : files) {
                totalSize += file.length();
            }
            basicDetailsText.setText("Items selected: " + files.size() + "\nTotal size: " + Formatter.formatFileSize(this, totalSize));
        }

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
					Toast.makeText(FileDeleteActivity.this, "Summary copied to clipboard.", Toast.LENGTH_SHORT).show();
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
                            Toast.makeText(FileDeleteActivity.this, "Receiver username cannot be empty.", Toast.LENGTH_SHORT).show();
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


    private void initiateDeletionProcess() {
        final List<File> initiallySelectedFiles = getSelectedFiles();

        if (initiallySelectedFiles.isEmpty()) {
            Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean requiresSdCardPermission = false;
        for (File file : initiallySelectedFiles) {
            if (StorageUtils.isFileOnSdCard(this, file) && !StorageUtils.hasSdCardPermission(this)) {
                requiresSdCardPermission = true;
                break;
            }
        }

        if (requiresSdCardPermission) {
            mFilesPendingPermission = initiallySelectedFiles;
            mPendingOperation = new Runnable() {
                @Override
                public void run() {
                    showDeleteConfirmationDialog(initiallySelectedFiles);
                }
            };
            promptForSdCardPermission();
        } else {
            showDeleteConfirmationDialog(initiallySelectedFiles);
        }
    }

    private void showDeleteConfirmationDialog(final List<File> filesToConfirm) {
        final Set<File> masterDeleteSet = new HashSet<>();
        for (File selectedFile : filesToConfirm) {
            masterDeleteSet.addAll(findSiblingFiles(selectedFile));
        }

        final List<File> filesToDelete = new ArrayList<>(masterDeleteSet);
        String dialogMessage;

        if (filesToDelete.size() > filesToConfirm.size()) {
            int siblingCount = filesToDelete.size() - filesToConfirm.size();
            dialogMessage = "You selected <b>" + filesToConfirm.size() + "</b> file(s), but we found <b>" + siblingCount
				+ "</b> other related version(s) (e.g., with map overlays).<br/><br/>Are you sure you want to permanently delete all <b>"
				+ filesToDelete.size() + "</b> related files? This action cannot be undone.";
        } else {
            dialogMessage = "Are you sure you want to permanently delete " + filesToDelete.size() + " file(s)? This action cannot be undone.";
        }

        new AlertDialog.Builder(this)
			.setTitle("Confirm Deletion")
			.setMessage(Html.fromHtml(dialogMessage))
			.setPositiveButton("Delete All", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					performDeletion(filesToDelete);
				}
			})
			.setNeutralButton("Move to Recycle", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					moveToRecycleBin(filesToDelete);
				}
			})
			.setNegativeButton("Cancel", null)
			.show();
    }

    private void moveToRecycleBin(List<File> filesToMove) {
        new MoveToRecycleTask(filesToMove).execute();
    }

    private List<File> findSiblingFiles(File originalFile) {
        List<File> siblings = new ArrayList<>();
        siblings.add(originalFile);

        String fileName = originalFile.getName();
        Matcher matcher = FILE_BASE_NAME_PATTERN.matcher(fileName);

        if (matcher.find()) {
            String baseName = matcher.group(0);
            File parentDir = originalFile.getParentFile();

            if (parentDir != null && parentDir.isDirectory()) {
                File[] filesInDir = parentDir.listFiles();
                if (filesInDir != null) {
                    for (File potentialSibling : filesInDir) {
                        if (potentialSibling.isFile() && potentialSibling.getName().startsWith(baseName)) {
                            if (!potentialSibling.getAbsolutePath().equals(originalFile.getAbsolutePath())) {
                                siblings.add(potentialSibling);
                            }
                        }
                    }
                }
            }
        }
        return siblings;
    }

    private void performDeletion(List<File> filesToDelete) {
        ArrayList<String> filePathsToDelete = new ArrayList<>();
        for (File file : filesToDelete) {
            filePathsToDelete.add(file.getAbsolutePath());
        }

        deletionProgressLayout.setVisibility(View.VISIBLE);
        deletionProgressBar.setIndeterminate(true);
        deletionProgressText.setText("Starting deletion...");

        Intent intent = new Intent(this, DeleteService.class);
        intent.putStringArrayListExtra(DeleteService.EXTRA_FILES_TO_DELETE, filePathsToDelete);
        ContextCompat.startForegroundService(this, intent);
    }

    private void setupBroadcastReceivers() {
        deleteCompletionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int deletedCount = intent.getIntExtra(DeleteService.EXTRA_DELETED_COUNT, 0);
                Toast.makeText(FileDeleteActivity.this, "Deletion complete. " + deletedCount + " files removed.", Toast.LENGTH_LONG).show();

                deletionProgressLayout.setVisibility(View.GONE);

                Intent resultIntent = new Intent();
                FileDeleteActivity.this.setResult(Activity.RESULT_OK, resultIntent);
                finish();
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(deleteCompletionReceiver, new IntentFilter(DeleteService.ACTION_DELETE_COMPLETE));

        compressionBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean success = intent.getBooleanExtra(CompressionService.EXTRA_SUCCESS, false);
                if (success) {
                    FileDeleteActivity.this.setResult(Activity.RESULT_OK);
                    finish();
                }
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

    private int getFileCategory(String fileName) {
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase(Locale.ROOT);
        }

        List<String> imageExtensions = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp");
        List<String> videoExtensions = Arrays.asList("mp4", "3gp", "mkv", "webm", "avi");
        List<String> audioExtensions = Arrays.asList("mp3", "wav", "ogg", "m4a", "aac", "flac");
        List<String> scriptExtensions = Arrays.asList("json", "xml", "html", "js", "css", "java", "kt", "py", "c", "cpp", "h", "cs", "php", "rb", "go", "swift", "sh", "bat", "ps1", "ini", "cfg", "conf", "md", "prop", "gradle", "pro", "sql");
        List<String> docExtensions = Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv");

        if (imageExtensions.contains(extension)) return CATEGORY_IMAGES;
        if (videoExtensions.contains(extension)) return CATEGORY_VIDEOS;
        if (audioExtensions.contains(extension)) return CATEGORY_AUDIO;
        if (scriptExtensions.contains(extension)) return CATEGORY_SCRIPTS;
        if (docExtensions.contains(extension)) return CATEGORY_DOCS;
        return CATEGORY_OTHER;
    }

    private void openFileViewer(final File file) {
        new AsyncTask<Void, Void, Intent>() {
            @Override
            protected Intent doInBackground(Void... voids) {
                String path = file.getAbsolutePath();
                String name = file.getName();
                int category = getFileCategory(name);
                Intent intent = null;

                if (category == CATEGORY_IMAGES || category == CATEGORY_VIDEOS || category == CATEGORY_AUDIO) {
                    ArrayList<String> fileList = getSiblingFilesForViewer(file, category);
                    int currentIndex = fileList.indexOf(path);
                    if (currentIndex == -1) {
						return null;
                    }

                    if (category == CATEGORY_IMAGES) {
                        intent = new Intent(FileDeleteActivity.this, ImageViewerActivity.class);
                        intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(ImageViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    } else if (category == CATEGORY_VIDEOS) {
                        intent = new Intent(FileDeleteActivity.this, VideoViewerActivity.class);
                        intent.putStringArrayListExtra(VideoViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(VideoViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    } else if (category == CATEGORY_AUDIO) {
                        intent = new Intent(FileDeleteActivity.this, AudioPlayerActivity.class);
                        intent.putStringArrayListExtra(AudioPlayerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(AudioPlayerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    }
                } else {
                    if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                        intent = new Intent(FileDeleteActivity.this, PdfViewerActivity.class);
                    } else {
                        intent = new Intent(FileDeleteActivity.this, TextViewerActivity.class);
                    }
                    intent.putExtra(TextViewerActivity.EXTRA_FILE_PATH, path);
                }
                return intent;
            }

            @Override
            protected void onPostExecute(Intent intent) {
                if (intent != null) {
                    startActivity(intent);
                } else {
                    Toast.makeText(FileDeleteActivity.this, "Error opening file.", Toast.LENGTH_SHORT).show();
                }
            }
        }.execute();
    }

    private ArrayList<String> getSiblingFilesForViewer(File currentFile, final int category) {
        ArrayList<String> siblingFiles = new ArrayList<>();
        File parentDir = currentFile.getParentFile();
        if (parentDir == null || !parentDir.isDirectory()) {
            siblingFiles.add(currentFile.getAbsolutePath());
            return siblingFiles;
        }

        File[] filesInDir = parentDir.listFiles();
        if (filesInDir != null) {
            List<File> sortedFiles = new ArrayList<>(Arrays.asList(filesInDir));
            Collections.sort(sortedFiles, new Comparator<File>() {
					@Override
					public int compare(File f1, File f2) {
						return f1.getName().compareToIgnoreCase(f2.getName());
					}
				});

            for (File file : sortedFiles) {
                if (file.isFile() && getFileCategory(file.getName()) == category) {
                    siblingFiles.add(file.getAbsolutePath());
                }
            }
        }
        return siblingFiles;
    }

    private void promptForSdCardPermission() {
        new AlertDialog.Builder(this)
			.setTitle("SD Card Permission Needed")
			.setMessage("To delete files on your external SD card, you must grant this app access. Please tap 'Grant', then select the root of your SD card and tap 'Allow'.")
			.setPositiveButton("Grant", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					StorageUtils.requestSdCardPermission(FileDeleteActivity.this);
				}
			})
			.setNegativeButton("Cancel", null)
			.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == StorageUtils.REQUEST_CODE_SDCARD_PERMISSION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri treeUri = data.getData();
                if (treeUri != null) {
                    getContentResolver().takePersistableUriPermission(treeUri,
																	  Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                    StorageUtils.saveSdCardUri(this, treeUri);
                    Toast.makeText(this, "SD card access granted.", Toast.LENGTH_SHORT).show();

                    if (mPendingOperation != null) {
                        mPendingOperation.run();
                    } else if (mFilesPendingPermission != null && !mFilesPendingPermission.isEmpty()) {
                        showDeleteConfirmationDialog(mFilesPendingPermission);
                    }
                }
            } else {
                Toast.makeText(this, "SD card permission was not granted.", Toast.LENGTH_SHORT).show();
            }
            mFilesPendingPermission = null;
            mPendingOperation = null;
        }
    }

    private class MoveToRecycleTask extends AsyncTask<Void, Void, List<File>> {
        private AlertDialog progressDialog;
        private List<File> filesToMove;
        private Context context;

        public MoveToRecycleTask(List<File> filesToMove) {
            this.filesToMove = filesToMove;
            this.context = FileDeleteActivity.this;
        }

        @Override
        protected void onPreExecute() {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_progress_simple, null);
            builder.setView(dialogView);
            builder.setCancelable(false);
            progressDialog = builder.create();
            progressDialog.show();
        }

        @Override
        protected List<File> doInBackground(Void... voids) {
            File recycleBinDir = new File(Environment.getExternalStorageDirectory(), "HFMRecycleBin");
            if (!recycleBinDir.exists()) {
                if (!recycleBinDir.mkdir()) {
                    return new ArrayList<>();
                }
            }

            List<File> movedFiles = new ArrayList<>();
            for (File sourceFile : filesToMove) {
                if (sourceFile.exists()) {
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

                    boolean moveSuccess = false;

                    if (sourceFile.renameTo(destFile)) {
                        moveSuccess = true;
                    } else {
                        Log.w("FileDeleteActivity", "renameTo failed for " + sourceFile.getAbsolutePath() + ". Falling back to copy-delete.");
                        if (StorageUtils.copyFile(context, sourceFile, destFile)) {
                            if (StorageUtils.deleteFile(context, sourceFile)) {
                                moveSuccess = true;
                            } else {
                                Log.e("FileDeleteActivity", "Failed to delete original file " + sourceFile.getAbsolutePath() + " after copy. Deleting copied file to prevent duplication.");
                                destFile.delete();
                                moveSuccess = false;
                            }
                        } else {
                            Log.e("FileDeleteActivity", "Copy-delete fallback failed to copy file: " + sourceFile.getAbsolutePath());
                            moveSuccess = false;
                        }
                    }

                    if (moveSuccess) {
                        movedFiles.add(sourceFile);
                        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(sourceFile)));
                        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destFile)));
                    } else {
                        Log.w("FileDeleteActivity", "Failed to move file to recycle bin: " + sourceFile.getAbsolutePath());
                    }
                }
            }
            return movedFiles;
        }

        @Override
        protected void onPostExecute(List<File> movedFiles) {
            progressDialog.dismiss();

            if (movedFiles.isEmpty() && !filesToMove.isEmpty()) {
                Toast.makeText(context, "Failed to move some or all files.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, movedFiles.size() + " file(s) moved to Recycle Bin.", Toast.LENGTH_LONG).show();
            }

            if (!movedFiles.isEmpty()) {
                Intent resultIntent = new Intent();
                setResult(Activity.RESULT_OK, resultIntent);
                finish();
            }
        }
    }
}