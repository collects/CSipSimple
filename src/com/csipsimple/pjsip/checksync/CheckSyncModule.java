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
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

import com.csipsimple.api.SipProfile;
import com.csipsimple.pjsip.PjSipService.PjsipModule;
import com.csipsimple.utils.Log;

import org.pjsip.pjsua.pjsua;
import org.pjsip.pjsua.CheckSyncCallback;

/**
 * @author Duzy Chan
 */
public class CheckSyncModule implements PjsipModule {
    private static final String THIS_FILE = "CheckSyncModule";

    @Override
    public void setContext(Context ctxt) {
    }

    @Override
    public void onBeforeStartPjsip() {
        pjsua.mod_checksync_init(new Callback());
    }

    @Override
    public void onBeforeAccountStartRegistration(int pjId, SipProfile acc) {
	// TODO: ...
    }

    private class Callback extends CheckSyncCallback {
	public void on_event(String s) {
	    
	}
    }
}
