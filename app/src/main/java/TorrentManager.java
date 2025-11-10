package com.hfm.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.ErrorCode;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.StateUpdateAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TorrentFinishedAlert;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TorrentManager {

    private static final String TAG = "TorrentManager";
    private static volatile TorrentManager instance;

    private final SessionManager sessionManager;
    private final Context appContext;

    // Maps to track active torrents
    private final Map<String, TorrentHandle> activeTorrents; // dropRequestId -> TorrentHandle
    private final Map<Sha1Hash, String> hashToIdMap; // infoHash -> dropRequestId

    private TorrentManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sessionManager = new SessionManager();
        this.activeTorrents = new ConcurrentHashMap<>();
        this.hashToIdMap = new ConcurrentHashMap<>();

        // Set up the listener for torrent events
        sessionManager.addListener(new AlertListener() {
            @Override
            public int[] types() {
                // CORRECT API: Use the AlertType enum to get the integer values.
                return new int[]{
                        AlertType.STATE_UPDATE.swig(),
                        AlertType.TORRENT_FINISHED.swig(),
                        AlertType.TORRENT_ERROR.swig()
                };
            }

            @Override
            public void alert(Alert<?> alert) {
                switch (alert.type()) {
                    case STATE_UPDATE:
                        handleStateUpdate((StateUpdateAlert) alert);
                        break;
                    case TORRENT_FINISHED:
                        handleTorrentFinished((TorrentFinishedAlert) alert);
                        break;
                    case TORRENT_ERROR:
                        handleTorrentError((TorrentErrorAlert) alert);
                        break;
                }
            }
        });

        // Start the session, this will start the DHT and other services
        sessionManager.start();
    }

    public static TorrentManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TorrentManager.class) {
                if (instance == null) {
                    instance = new TorrentManager(context);
                }
            }
        }
        return instance;
    }

    private void handleStateUpdate(StateUpdateAlert alert) {
        for (TorrentStatus status : alert.status()) {
            // CORRECT API: The method is .infoHash()
            String dropRequestId = hashToIdMap.get(status.infoHash());
            if (dropRequestId != null) {
                Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR, status.isSeeding() ? "Sending File..." : "Receiving File...");
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR, "Peers: " + status.numPeers() + " | ↓ " + (status.downloadPayloadRate() / 1024) + " KB/s | ↑ " + (status.uploadPayloadRate() / 1024) + " KB/s");
                intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, (int) status.totalDone());
                intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, (int) status.totalWanted());
                intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, status.totalDone());
                LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
            }
        }
    }

    private void handleTorrentFinished(TorrentFinishedAlert alert) {
        TorrentHandle handle = alert.handle();
        String dropRequestId = hashToIdMap.get(handle.infoHash());
        Log.d(TAG, "Torrent finished for request ID: " + dropRequestId);

        if (dropRequestId != null) {
            Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
        }

        // Cleanup
        cleanupTorrent(handle);
    }

    private void handleTorrentError(TorrentErrorAlert alert) {
        TorrentHandle handle = alert.handle();
        String dropRequestId = hashToIdMap.get(handle.infoHash());
        // CORRECT API: Get the ErrorCode object, then get its message.
        String errorMsg = alert.error().message();
        Log.e(TAG, "Torrent error for request ID " + dropRequestId + ": " + errorMsg);

        if (dropRequestId != null) {
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Torrent transfer failed: " + errorMsg);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);

            LocalBroadcastManager.getInstance(appContext).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
        }

        // Cleanup
        cleanupTorrent(handle);
    }

    public String startSeeding(File file, String dropRequestId) {
        if (file == null || !file.exists()) {
            Log.e(TAG, "File to be seeded does not exist.");
            return null;
        }

        // CORRECT API: Use a constructor for TorrentInfo.
        final TorrentInfo torrentInfo = new TorrentInfo(file);
        
        AddTorrentParams params = new AddTorrentParams();
        // CORRECT API: The method is .torrentInfo(ti)
        params.torrentInfo(torrentInfo);
        // CORRECT API: The method is .savePath(string)
        params.savePath(file.getParent());
        
        // CORRECT API: The method is .addTorrent(params)
        sessionManager.addTorrent(params);
        // CORRECT API: The method is .findTorrent(hash)
        TorrentHandle handle = sessionManager.findTorrent(torrentInfo.infoHash());

        if (handle != null) {
            activeTorrents.put(dropRequestId, handle);
            hashToIdMap.put(handle.infoHash(), dropRequestId);
            // CORRECT API: The method is .magnetUri()
            String magnetLink = handle.magnetUri();
            Log.d(TAG, "Started seeding for request ID " + dropRequestId + ". Magnet: " + magnetLink);
            return magnetLink;
        } else {
            Log.e(TAG, "Failed to get TorrentHandle after adding seed.");
            return null;
        }
    }

    public void startDownload(String magnetLink, File saveDirectory, String dropRequestId) {
        if (!saveDirectory.exists()) {
            saveDirectory.mkdirs();
        }

        // CORRECT API: This method is static on SessionManager.
        AddTorrentParams params = sessionManager.parseMagnetUri(magnetLink);
        params.savePath(saveDirectory.getAbsolutePath());
        sessionManager.addTorrent(params);
        // CORRECT API: The method is .infoHash()
        TorrentHandle handle = sessionManager.findTorrent(params.infoHash());

        if (handle != null) {
            activeTorrents.put(dropRequestId, handle);
            hashToIdMap.put(handle.infoHash(), dropRequestId);
            Log.d(TAG, "Started download for request ID: " + dropRequestId);
        } else {
            Log.e(TAG, "Failed to get TorrentHandle after adding download from magnet link.");
        }
    }

    private void cleanupTorrent(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) {
            return;
        }
        Sha1Hash hash = handle.infoHash();
        String dropRequestId = hashToIdMap.get(hash);

        if (dropRequestId != null) {
            activeTorrents.remove(dropRequestId);
            hashToIdMap.remove(hash);
        }
        // CORRECT API: The method is indeed .removeTorrent(handle).
        sessionManager.removeTorrent(handle);
        Log.d(TAG, "Cleaned up and removed torrent for request ID: " + dropRequestId);
    }

    public void stopSession() {
        Log.d(TAG, "Stopping torrent session manager.");
        sessionManager.stop();
        activeTorrents.clear();
        hashToIdMap.clear();
        instance = null; // Allow re-initialization if needed
    }
}