package de.fh_wedel.phone_tracker;

import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Does the actual scanning of BSSIDs in the background.
 */
public class GatherBSSID extends Service {

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        GatherBSSID getService() {
            // Return this instance of LocalService so clients can call public methods
            return GatherBSSID.this;
        }
    }


    private static String TAG = "GATHER_BSSID";

    public static String BROADCAST_ACTION = "fhw.gather.broadcast";

    public static String KEY_BROADCAST_SCANS = "scans";

    private WifiManager wifi;

    private boolean running = true;

    /**
     * Needed to properly unregister
     */
    private BroadcastReceiver scanListener;

    /**
     * Contains information about all currently known BSSIDs
     */
    private Map<String,ScanResult> knownBSSIDs = new HashMap<>();

    /**
     * The actual configuration of this gathering service.
     */
    private ListWLANConfig config;

    /**
     * The most current set of available scan results
     */
    private ScanResult[] scanResults;

    /**
     * Returned to the call to ease IPC.
     */
    private final IBinder mBinder = new LocalBinder();

    /**
     * Throws away all known scan results and stores new scan results.
     */
    public void refreshScanResults() {
        knownBSSIDs.clear();
        List<ScanResult> results = wifi.getScanResults();
        try {
            if (results != null) {
                for (ScanResult sc : results) {
                    if (config.isInterestingSSID(sc.SSID)) {
                        knownBSSIDs.put(sc.BSSID, sc);
                    }
                }

                Intent localIntent = new Intent(BROADCAST_ACTION);

                scanResults = knownBSSIDs.values().toArray(new ScanResult[knownBSSIDs.size()]);
                localIntent.putExtra(KEY_BROADCAST_SCANS, scanResults);

                LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent);

                Log.i(TAG, String.format("Broadcasted %d interesting APs", scanResults.length));

                if (config.getAutoSend()) {
                    send(scanResults);
                }
            }
        }
        catch (Exception e)
        {
            Log.e(TAG, "Error processing scan results", e);
        }
    }

    /**
     * Start of lifecycle, initially grabs a configuration file and starts listening for AP
     * broadcasts.
     * @param intent
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onCreate();

        Log.i(TAG, "BSSID listening service has been started");

        // Retrieve the configuration from the intent
        config = intent.getExtras().getParcelable(ListWLAN.STATE_KEY_CONFIG);
        assert(config != null);

        // Notify the user about what's going on ...
        GatherNotificiation.ShowActive(getNotificationManager(), this, config);

        // Listener for scan results
        scanListener = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshScanResults();
            }
        };

        // Listen for found access points and immediatly do a first scan
        wifi = (WifiManager)getSystemService(Context.WIFI_SERVICE);
        registerReceiver(scanListener, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        wifi.startScan();

        return (Service.START_STICKY);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;

        unregisterReceiver(scanListener);
        GatherNotificiation.Stop(getNotificationManager());

        Log.i(TAG, "Told main loop to stop");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Send JSON data to the server in a background task
     *
     * @param json Arbitrary JSON data
     */
    private void postDataAsync(JSONObject json) {
        final GatherBSSID self = this;

        class PostDataTask extends AsyncTask<JSONObject, Void, Exception> {
            @Override
            protected Exception doInBackground(JSONObject... params) {
                return self.postData(params[0]);
            }

            @Override
            protected void onPostExecute(Exception result) {
                if (result == null) {
                    //self.textView.append("Send finished\n");
                } else {
                    //self.textView.append(String.format("Send failed: %s\n", result.getMessage()));
                }
            }
        }
        //ListWLAN.this.textView.append(String.format("Sending found BSSIDs to \"%s\"...\n", config.getServerUrl()));
        new PostDataTask().execute(json);
    }

    /**
     * Sends the most recent results to the server.
     */
    public void sendLatestResults() {
        send(scanResults);
    }

    /**
     * Sends known access points to the phone tracker server.
     */
    private void send(ScanResult[] scanResults) {
        if (scanResults == null) {
            return;
        }

        if (wifi.isWifiEnabled() == true) {
            JSONObject json = new JSONObject();
            try {
                json.put("phone", config.getPhoneID());
                json.put("comment", "");
                for (ScanResult entry : scanResults) {
                    JSONObject ap = new JSONObject();
                    ap.put("bssid", entry.BSSID);
                    ap.put("level", entry.level);
                    ap.put("ssid", entry.SSID);
                    json.accumulate("stations", ap);
                }
                //textView.append(json.toString());
            } catch (JSONException e) {

                Log.e(TAG, "Some JSON exception", e);
            }
            //postData(json);
            postDataAsync(json);
        }
    }

    /**
     * Send JSON data to the server on the foreground thread
     *
     * @param json Arbitrary JSON data
     */
    private Exception postData(JSONObject json) {
        // from http://androidsnippets.com/executing-a-http-post-request-with-httpclient
        // and http://stackoverflow.com/questions/6218143/how-to-send-post-request-in-json-using-httpclient
        DefaultHttpClient httpclient = new DefaultHttpClient();
        HttpPost httppost = new HttpPost(config.getServerUrl());
        try {
            StringEntity se = new StringEntity(json.toString());
            httppost.setEntity(se);
            //httppost.setHeader("Accept", "application/json");
            httppost.setHeader("Content-type", "application/json");
            HttpResponse response = httpclient.execute(httppost);

            return null;
        } catch (ClientProtocolException e) {
            String debugOutput = String.format("ClientProtocolException occurred: %s\n", e);
            Log.e(TAG, debugOutput, e);

            return e;
        } catch (IOException e) {
            String debugOutput = String.format("IOException occurred: %s\n", e);
            Log.e(TAG, debugOutput, e);

            return e;
        }
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
