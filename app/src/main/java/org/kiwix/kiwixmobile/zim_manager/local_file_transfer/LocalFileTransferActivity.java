package org.kiwix.kiwixmobile.zim_manager.local_file_transfer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnItemClick;
import java.net.InetAddress;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.kiwix.kiwixmobile.KiwixApplication;
import org.kiwix.kiwixmobile.R;
import org.kiwix.kiwixmobile.utils.AlertDialogShower;
import org.kiwix.kiwixmobile.utils.KiwixDialog;
import org.kiwix.kiwixmobile.utils.SharedPreferenceUtil;

import java.util.ArrayList;

import javax.inject.Inject;

import static org.kiwix.kiwixmobile.zim_manager.local_file_transfer.FileItem.FileStatus.TO_BE_SENT;
import static org.kiwix.kiwixmobile.zim_manager.local_file_transfer.WifiDirectManager.getDeviceStatus;
import static org.kiwix.kiwixmobile.zim_manager.local_file_transfer.WifiDirectManager.getFileName;

/**
 * Created by @Aditya-Sood as a part of GSoC 2019.
 *
 * This activity is the starting point for the module used for sharing zims between devices.
 *
 * The module is used for transferring ZIM files from one device to another, from within the
 * app. Two devices are connected to each other using WiFi Direct, followed by file transfer.
 *
 * File transfer involves two phases:
 * 1) Handshake with the selected peer device, using {@link PeerGroupHandshakeAsyncTask}
 * 2) After handshake, starting the files transfer using {@link SenderDeviceAsyncTask} on the sender
 * device and {@link ReceiverDeviceAsyncTask} files receiving device
 */
@SuppressLint("GoogleAppIndexingApiWarning")
public class LocalFileTransferActivity extends AppCompatActivity implements
    WifiDirectManager.Callbacks {

  // Not a typo, 'Log' tags have a length upper limit of 25 characters
  public static final String TAG = "LocalFileTransferActvty";
  public static final int REQUEST_ENABLE_LOCATION_SERVICES = 1;
  private static final int PERMISSION_REQUEST_CODE_COARSE_LOCATION = 1;
  private static final int PERMISSION_REQUEST_CODE_STORAGE_WRITE_ACCESS = 2;

  @Inject SharedPreferenceUtil sharedPreferenceUtil;
  @Inject AlertDialogShower alertDialogShower;

  @BindView(R.id.toolbar_local_file_transfer) Toolbar actionBar;
  @BindView(R.id.text_view_device_name) TextView deviceName;
  @BindView(R.id.progress_bar_searching_peers) ProgressBar searchingPeersProgressBar;
  @BindView(R.id.list_peer_devices) ListView listViewPeerDevices;
  @BindView(R.id.text_view_empty_peer_list) TextView textViewPeerDevices;
  @BindView(R.id.recycler_view_transfer_files) RecyclerView filesRecyclerView;

  private ArrayList<Uri> fileUriArrayList;// For sender device, stores uris of the files
  public boolean isFileSender = false;    // Whether the device is the file sender or not

  public @NonNull WifiDirectManager wifiDirectManager = new WifiDirectManager(this);

  private ArrayList<FileItem> filesToSend = new ArrayList<>();
  private FileListAdapter fileListAdapter;

  private List<WifiP2pDevice> peerDevices = new ArrayList<WifiP2pDevice>();
  private boolean fileTransferStarted = false;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    KiwixApplication.getApplicationComponent().activityComponent()
        .activity(this)
        .build()
        .inject(this);
    setTheme(sharedPreferenceUtil.nightMode() ? R.style.Theme_AppCompat_DayNight_NoActionBar : R.style.AppTheme);
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_local_file_transfer);
    ButterKnife.bind(this);

    /*
     * Presence of file Uris decides whether the device with the activity open is a sender or receiver:
     * - On the sender device, this activity is started from the app chooser post selection
     * of files to share in the Library
     * - On the receiver device, the activity is started directly from within the 'Get Content'
     * activity, without any file Uris
     * */
    Intent filesIntent = getIntent();
    fileUriArrayList = filesIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
    isFileSender = (fileUriArrayList != null && fileUriArrayList.size() > 0);

    setSupportActionBar(actionBar);
    actionBar.setNavigationIcon(R.drawable.ic_close_white_24dp);
    actionBar.setNavigationOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        finish();
      }
    });

    wifiDirectManager.createWifiDirectManager(sharedPreferenceUtil, alertDialogShower, fileUriArrayList);

    listViewPeerDevices.setAdapter(
        new WifiPeerListAdapter(this, R.layout.row_peer_device, peerDevices));

    if (isFileSender) {
      for (int i = 0; i < fileUriArrayList.size(); i++) {
        filesToSend.add(new FileItem(getFileName(fileUriArrayList.get(i)), TO_BE_SENT));
      }

      displayFileTransferProgress(filesToSend);
    }
  }

  @OnItemClick(R.id.list_peer_devices)
  void onItemClick(int position) {
    /* Connection can only be initiated by user of the sender device, & only when transfer has not been started */
    if (!isFileSender || fileTransferStarted) {
      return;
    }

    WifiP2pDevice senderSelectedPeerDevice =
        (WifiP2pDevice) listViewPeerDevices.getAdapter().getItem(position);
    wifiDirectManager.sendToDevice(senderSelectedPeerDevice);
  }

  @Override
  public boolean onCreateOptionsMenu(@NonNull Menu menu) {
    getMenuInflater().inflate(R.menu.wifi_file_share_items, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == R.id.menu_item_search_devices) {

      /* Permissions essential for this module */
      if (!checkCoarseLocationAccessPermission()) {
        return true;
      }

      if (!checkExternalStorageWritePermission()) {
        return true;
      }

      /* Initiate discovery */
      if (!wifiDirectManager.isWifiP2pEnabled()) {
        requestEnableWifiP2pServices();
        return true;
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isLocationServicesEnabled()) {
        requestEnableLocationServices();
        return true;
      }

      onInitiateDiscovery();
      wifiDirectManager.discoverPeerDevices();

      return true;
    } else {
      return super.onOptionsItemSelected(item);
    }
  }

  /* Helper methods used in the activity */
  public void updateUserDevice(@Nullable WifiP2pDevice userDevice) { // Update UI with user device's details
    wifiDirectManager.setUserDevice(userDevice);

    if (userDevice != null) {
      deviceName.setText(userDevice.deviceName);
      Log.d(TAG, getDeviceStatus(userDevice.status));
    }
  }

  public void clearPeers() {
    peerDevices.clear();
    ((WifiPeerListAdapter) listViewPeerDevices.getAdapter()).notifyDataSetChanged();
  }

  @Override
  public void displayFileTransferProgress(ArrayList<FileItem> filesToSend) {
    if(!isFileSender) {
      this.filesToSend = filesToSend;
    }
    fileListAdapter = new FileListAdapter(filesToSend);
    filesRecyclerView.setAdapter(fileListAdapter);
    filesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
  }

  public void onInitiateDiscovery() { // Setup UI for searching peers
    searchingPeersProgressBar.setVisibility(View.VISIBLE);
    listViewPeerDevices.setVisibility(View.INVISIBLE);
    textViewPeerDevices.setVisibility(View.INVISIBLE);
  }

  public void changeStatus(int itemIndex, @FileItem.FileStatus int status) {
    filesToSend.get(itemIndex).setFileStatus(status);
    fileListAdapter.notifyItemChanged(itemIndex);
  }

  /* From WifiDirectManager.Callbacks interface */
  @Override
  public void updatePeerDevicesList(@NonNull WifiP2pDeviceList peers) {
    searchingPeersProgressBar.setVisibility(View.GONE);
    listViewPeerDevices.setVisibility(View.VISIBLE);

    peerDevices.clear();
    peerDevices.addAll(peers.getDeviceList());
    ((WifiPeerListAdapter) listViewPeerDevices.getAdapter()).notifyDataSetChanged();

    if (peerDevices.size() == 0) {
      Log.d(LocalFileTransferActivity.TAG, "No devices found");
    }
  }

  /* Helper methods used for checking permissions and states of services */
  private boolean checkCoarseLocationAccessPermission() { // Required by Android to detect wifi-p2p peers
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        == PackageManager.PERMISSION_DENIED) {

      if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)) {
        alertDialogShower.show(KiwixDialog.LocationPermissionRationale.INSTANCE,
            new Function0<Unit>() {
              @Override public Unit invoke() {
                ActivityCompat.requestPermissions(LocalFileTransferActivity.this, new String[] { Manifest.permission.ACCESS_COARSE_LOCATION },
                    PERMISSION_REQUEST_CODE_COARSE_LOCATION);
                return Unit.INSTANCE;
              }
            });
      } else {
        ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.ACCESS_COARSE_LOCATION },
            PERMISSION_REQUEST_CODE_COARSE_LOCATION);
      }
      return false;

    } else {
      return true; // Control reaches here: Either permission granted at install time, or at the time of request
    }
  }

  private boolean checkExternalStorageWritePermission() { // To access and store the zims
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        == PackageManager.PERMISSION_DENIED) {

      if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
        alertDialogShower.show(KiwixDialog.StoragePermissionRationale.INSTANCE,
            new Function0<Unit>() {
              @Override public Unit invoke() {
                ActivityCompat.requestPermissions(LocalFileTransferActivity.this, new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
                    PERMISSION_REQUEST_CODE_STORAGE_WRITE_ACCESS);
                return Unit.INSTANCE;
              }
            });
      } else {
        ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
            PERMISSION_REQUEST_CODE_STORAGE_WRITE_ACCESS);
      }
      return false;

    } else {
      return true; // Control reaches here: Either permission granted at install time, or at the time of request
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
      @NonNull int[] grantResults) {

    if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
      switch (requestCode) {

        case PERMISSION_REQUEST_CODE_COARSE_LOCATION: {
          Log.e(TAG, "Location permission not granted");

          showToast(this, R.string.permission_refused_location, Toast.LENGTH_LONG);
          finish();
          break;
        }

        case PERMISSION_REQUEST_CODE_STORAGE_WRITE_ACCESS: {
          Log.e(TAG, "Storage write permission not granted");

          showToast(this, R.string.permission_refused_storage, Toast.LENGTH_LONG);
          finish();
          break;
        }

        default: {
          super.onRequestPermissionsResult(requestCode, permissions, grantResults);
          break;
        }
      }
    }
  }

  private boolean isLocationServicesEnabled() {
    LocationManager locationManager =
        (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
    boolean gps_enabled = false;
    boolean network_enabled = false;

    try {
      gps_enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    } catch (Exception ex) {
      ex.printStackTrace();
    }

    try {
      network_enabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    } catch (Exception ex) {
      ex.printStackTrace();
    }

    return (gps_enabled || network_enabled);
  }

  private void requestEnableLocationServices() {
    alertDialogShower.show(KiwixDialog.EnableLocationServices.INSTANCE,
        new Function0<Unit>() {
          @Override public Unit invoke() {
            startActivityForResult(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                REQUEST_ENABLE_LOCATION_SERVICES);
            return Unit.INSTANCE;
          }
        },
        new Function0<Unit>() {
          @Override public Unit invoke() {
            showToast(LocalFileTransferActivity.this, R.string.discovery_needs_location,
                Toast.LENGTH_SHORT);
            return Unit.INSTANCE;
          }
        });
  }

  private void requestEnableWifiP2pServices() {
    alertDialogShower.show(KiwixDialog.EnableWifiP2pServices.INSTANCE,
        new Function0<Unit>() {
          @Override public Unit invoke() {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return Unit.INSTANCE;
          }
        },
        new Function0<Unit>() {
          @Override public Unit invoke() {
            showToast(LocalFileTransferActivity.this, R.string.discovery_needs_wifi,
                Toast.LENGTH_SHORT);
            return Unit.INSTANCE;
          }
        });
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    switch (requestCode) {
      case REQUEST_ENABLE_LOCATION_SERVICES: {
        LocationManager locationManager =
            (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);

        if (!(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))) {
          // If neither provider is enabled
          showToast(this, R.string.permission_refused_location, Toast.LENGTH_LONG);
        }
        break;
      }

      default: {
        super.onActivityResult(requestCode, resultCode, data);
        break;
      }
    }
  }

  /* Miscellaneous helper methods */
  static void showToast(Context context, int stringResource, int duration) {
    showToast(context, context.getString(stringResource), duration);
  }

  static void showToast(Context context, String text, int duration) {
    Toast.makeText(context, text, duration).show();
  }

  @Override
  public void onResume() {
    super.onResume();
    wifiDirectManager.registerWifiDirectBroadcastRecevier();
  }

  @Override
  public void onPause() {
    super.onPause();
    wifiDirectManager.unregisterWifiDirectBroadcastRecevier();
  }

  @Override protected void onDestroy() {
    super.onDestroy();
    wifiDirectManager.destroyWifiDirectManager();
  }
}
