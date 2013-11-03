/**
 * Copyright (C) 2013 Duzy Chan <code@duzy.info>
 *
 *  CSipSimple is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  If you own a pjsip commercial license you can also redistribute it
 *  and/or modify it under the terms of the GNU Lesser General Public License
 *  as an android library.
 *
 *  CSipSimple is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with CSipSimple.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * 
 */

package com.csipsimple.pjsip.checksync;

import android.content.Context;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

import com.csipsimple.api.SipProfile;
import com.csipsimple.backup.SipProfileJson;
import com.csipsimple.pjsip.PjSipService.PjsipModule;
import com.csipsimple.utils.PreferencesWrapper;
import com.csipsimple.utils.Log;

import org.json.JSONException;
import org.pjsip.pjsua.pjsua;
import org.pjsip.pjsua.CheckSyncCallback;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Date;
import java.util.Map;

/**
 * @author Duzy Chan
 */
public class CheckSyncModule implements PjsipModule {
    private static final String THIS_FILE = "CheckSyncModule";

    private Context context = null;

    @Override
    public void setContext(Context ctxt) {
	context = ctxt;
    }

    @Override
    public void onBeforeStartPjsip() {
        pjsua.mod_checksync_init(new Callback());
    }

    @Override
    public void onBeforeAccountStartRegistration(int pjId, SipProfile acc) {
	// TODO: ...
    }

    private class AsyncProvision extends AsyncTask<Void, Void, Void> {
	@Override protected Void doInBackground(Void... vs) {
	    try {
		URL url = new URL("http://htwerk.com/prov2.json");
		URLConnection conexion = url.openConnection();
		conexion.connect();
		int lenghtOfFile = conexion.getContentLength();
		InputStream is = url.openStream();
		File dir = PreferencesWrapper.getConfigFolder(context);
		File file = new File(dir.getAbsoluteFile() + File.separator + "downloaded_config.file_part");
		FileOutputStream fos = new FileOutputStream(file);
		byte data[] = new byte[1024];
		int count = 0;
		long total = 0;
		int progress = 0;
		while ((count = is.read(data)) != -1) {
		    total += count;
		    int progress_temp = (int) total * 100 / lenghtOfFile;
		    if (progress_temp % 10 == 0 && progress != progress_temp) {
			progress = progress_temp;
		    }
		    fos.write(data, 0, count);
		}
		is.close();
		fos.close();

		File config = new File(dir.getAbsoluteFile() + File.separator + "downloaded_config.json");
		if (config.exists()) config.delete();
		file.renameTo(config);
		SipProfileJson.restoreSipConfiguration(context, config);
	    } catch (MalformedURLException e) {
		Log.e(THIS_FILE, "MalformedURLException", e);
	    } catch (FileNotFoundException e) {
		Log.e(THIS_FILE, "FileNotFoundException", e);
	    } catch (IOException e) {
		Log.e(THIS_FILE, "IOException", e);
	    }
	    return null;
	}
    }

    private class Callback extends CheckSyncCallback {
	public void on_event(String s) {
	    Log.d(THIS_FILE, s);
	    new AsyncProvision().execute();
	}
    }
}
