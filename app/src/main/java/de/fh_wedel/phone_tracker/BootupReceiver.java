package de.fh_wedel.phone_tracker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Created by marcus on 23/06/15.
 */
public class BootupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent orig) {
        // This probably runs the service all the time, once should be enough
        /*AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getService(context, 0, new Intent(context, GatherBSSID.class), PendingIntent.FLAG_UPDATE_CURRENT);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + interval, interval, pi);*/

        Intent intent = new Intent(context, GatherBSSID.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startService(intent);

    }
}
