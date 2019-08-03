package org.kiwix.kiwixmobile.zim_manager.local_file_transfer;

import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import org.kiwix.kiwixmobile.BuildConfig;
import org.kiwix.kiwixmobile.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

import static org.kiwix.kiwixmobile.zim_manager.local_file_transfer.FileItem.FileStatus.ERROR;
import static org.kiwix.kiwixmobile.zim_manager.local_file_transfer.FileItem.FileStatus.SENDING;
import static org.kiwix.kiwixmobile.zim_manager.local_file_transfer.FileItem.FileStatus.SENT;
import static org.kiwix.kiwixmobile.zim_manager.local_file_transfer.WifiDirectManager.FILE_TRANSFER_PORT;
import static org.kiwix.kiwixmobile.zim_manager.local_file_transfer.WifiDirectManager.copyToOutputStream;
import static org.kiwix.kiwixmobile.zim_manager.local_file_transfer.LocalFileTransferActivity.showToast;

/**
 * Helper class for the local file sharing module.
 *
 * Once the handshake has successfully taken place, this async-task is used to receive files from
 * the sender device on the FILE_TRANSFER_PORT port. No. of files to be received (and their names)
 * are learnt beforehand during the handshake.
 *
 * A single Task is used for the entire file transfer (the server socket accepts connections as
 * many times as the no. of files).
 */
class ReceiverDeviceAsyncTask extends AsyncTask<Void, Integer, Boolean> {

  private static final String TAG = "ReceiverDeviceAsyncTask";

  private WeakReference<LocalFileTransferActivity> weakReferenceToActivity;
  private int fileItemIndex;
  private String incomingFileName;

  public ReceiverDeviceAsyncTask(LocalFileTransferActivity localFileTransferActivity) {
    this.weakReferenceToActivity = new WeakReference<>(localFileTransferActivity);
  }

  @Override
  protected Boolean doInBackground(Void... voids) {
    try (ServerSocket serverSocket = new ServerSocket(FILE_TRANSFER_PORT)) {
      if (BuildConfig.DEBUG) Log.d(TAG, "Server: Socket opened at " + FILE_TRANSFER_PORT);

      final LocalFileTransferActivity localFileTransferActivity = weakReferenceToActivity.get();
      final String KIWIX_ROOT = localFileTransferActivity.wifiDirectManager.getZimStorageRootPath();
      int totalFileCount = localFileTransferActivity.wifiDirectManager.getTotalFilesForTransfer();
      boolean result = true;

      if (BuildConfig.DEBUG) Log.d(TAG, "Expecting "+totalFileCount+" files");

      for (int currentFile = 1; currentFile <= totalFileCount && !isCancelled(); currentFile++) {
        fileItemIndex = currentFile - 1;
        ArrayList<FileItem> fileItems = localFileTransferActivity.wifiDirectManager.getFileItems();
        incomingFileName = fileItems.get(fileItemIndex).getFileName();

        try (Socket client = serverSocket.accept()) {
          if (BuildConfig.DEBUG) Log.d(TAG, "Server: Client connected for file " + currentFile);
          publishProgress(fileItemIndex, SENDING);

          final File clientNoteFileLocation = new File(KIWIX_ROOT + incomingFileName);
          File dirs = new File(clientNoteFileLocation.getParent());
          if (!dirs.exists() && !dirs.mkdirs()) {
            Log.d(TAG, "ERROR: Required parent directories couldn't be created");
            result = false;
            continue;
          }

          boolean fileCreated = clientNoteFileLocation.createNewFile();
          if (BuildConfig.DEBUG) Log.d(TAG, "File creation: " + fileCreated);

          copyToOutputStream(client.getInputStream(), new FileOutputStream(clientNoteFileLocation));
          publishProgress(fileItemIndex, SENT);

        } catch (IOException e) {
          Log.e(TAG, e.getMessage());
          result = false;
          publishProgress(fileItemIndex, ERROR);
        }

        localFileTransferActivity.wifiDirectManager.incrementTotalFilesSent();
      }

      return (!isCancelled() && result);

    } catch (IOException e) {
      Log.e(TAG, e.getMessage());
      return false; // Returned when an error was encountered during transfer
    }
  }

  @Override
  protected void onProgressUpdate(Integer... values) {
    int fileIndex = values[0];
    int fileStatus = values[1];
    final LocalFileTransferActivity localFileTransferActivity = weakReferenceToActivity.get();
    localFileTransferActivity.changeStatus(fileIndex, fileStatus);

    if(fileStatus == ERROR) {
      showToast(localFileTransferActivity, localFileTransferActivity.getString(R.string.error_transferring, incomingFileName), Toast.LENGTH_SHORT);
    }
  }

  @Override protected void onCancelled() {
    Log.d(TAG, "ReceiverDeviceAsyncTask cancelled");
  }

  @Override
  protected void onPostExecute(Boolean allFilesReceived) {
    if (BuildConfig.DEBUG) Log.d(TAG, "File transfer complete");

    final LocalFileTransferActivity localFileTransferActivity = weakReferenceToActivity.get();
    if (allFilesReceived) {
      showToast(localFileTransferActivity, R.string.file_transfer_complete,
          Toast.LENGTH_LONG);
    } else {
      showToast(localFileTransferActivity, R.string.error_during_transfer,
          Toast.LENGTH_LONG);
    }

    localFileTransferActivity.finish();
  }
}
