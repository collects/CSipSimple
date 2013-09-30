/**
 * Copyright (C) 2010-2012 Regis Montoya (aka r3gis - www.r3gis.fr)
 * This file is part of CSipSimple.
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

package com.csipsimple.service;

import android.content.ContentUris;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.os.Handler;

import com.csipsimple.api.SipManager;
import com.csipsimple.api.SipProfile;
import com.csipsimple.api.BuddyState;
import com.csipsimple.service.SipService.SameThreadException;
import com.csipsimple.service.SipService.SipRunnable;
import com.csipsimple.utils.AccountListUtils;
import com.csipsimple.utils.AccountListUtils.AccountStatusDisplay;
import com.csipsimple.utils.Log;
import com.csipsimple.utils.contacts.ContactsWrapper;

import java.util.ArrayList;
import java.util.List;

public class PresenceManager
{
    private static final String THIS_FILE = "PresenceManager";

    private static final String[] ACC_PROJECTION = new String[] {
            SipProfile.FIELD_ID,
            SipProfile.FIELD_DISPLAY_NAME,
            SipProfile.FIELD_REG_URI,
            SipProfile.FIELD_WIZARD
    };

    private static final String[] BUD_PROJECTION = new String[] {
	SipProfile.FIELD_CONTACT,
	//SipProfile.FIELD_ACCOUNT,
	//SipProfile.FIELD_DISPLAY_NAME,
	//SipProfile.FIELD_SUBSCRIBE,
    };

    private SipService service;

    private final Handler mHandler = new Handler();
    private ArrayList<SipProfile> addedAccounts = new ArrayList<SipProfile>();

    private AccountStatusContentObserver statusObserver;
    private BuddyStatusContentObserver buddyStatusObserver;

    public synchronized void startMonitoring(SipService srv) {
        service = srv;
        if (statusObserver == null) {
            statusObserver = new AccountStatusContentObserver(mHandler);
            service.getContentResolver().registerContentObserver(SipProfile.ACCOUNT_STATUS_URI, true, statusObserver);
        }
	if (buddyStatusObserver == null) {
	    buddyStatusObserver = new BuddyStatusContentObserver(mHandler);
            service.getContentResolver().registerContentObserver(SipProfile.BUDDY_STATUS_URI, true, buddyStatusObserver);
	}
    }

    public synchronized void stopMonitoring() {
        if (statusObserver != null) {
            service.getContentResolver().unregisterContentObserver(statusObserver);
            statusObserver = null;
        }
	if (buddyStatusObserver != null) {
            service.getContentResolver().unregisterContentObserver(buddyStatusObserver);
	    buddyStatusObserver = null;
	}
        service = null;
    }
    
    
    /**
     * Get buddies sip uris associated with a sip profile
     * @param acc the profile to search in
     * @return a list of sip uris
     */
    private synchronized List<String> getBuddiesForAccount(SipProfile acc) {
        if(service != null) {
            List<String> buddies = ContactsWrapper.getInstance().getCSipPhonesByGroup(service, acc.display_name);
	    if (buddies == null) buddies = new ArrayList<String>();
	    final Cursor c = service.getContentResolver().query(SipProfile.BUDDY_URI, BUD_PROJECTION, SipProfile.FIELD_ACCOUNT+"=?", new String[]{""+acc.id}, null);
	    Log.d(THIS_FILE, c.getCount() + " buddies of account " + acc.id);
	    if (c != null && c.moveToFirst()) {
		String u = acc.reg_uri;
		if (u == null || u.isEmpty()) {
		    Log.e(THIS_FILE, " empty uri of account " + acc.id);
		    return buddies;
		}
		u = u.replace("sip:", "");
		do {
		    final ContentValues v = new ContentValues();
		    DatabaseUtils.cursorRowToContentValues(c, v);
		    String s = v.getAsString(SipProfile.FIELD_CONTACT)+"@"+u;
		    buddies.add(s);
		    Log.d(THIS_FILE, "buddy " + s);
		} while (c.moveToNext());
	    }
	    if (c != null) c.close();
	    return buddies;
        } else {
            return new ArrayList<String>();
        }
    }

    /**
     * Add buddies for a given account
     * @param acc
     */
    private synchronized void addBuddiesForAccount(SipProfile acc) {
        // Get buddies uris for this account
        final List<String> toAdd = getBuddiesForAccount(acc);
        if (toAdd.size() > 0 && service != null) {
            service.getExecutor().execute(new SipRunnable() { @Override protected void doRun() throws SameThreadException {
		for (String csipUri : toAdd) {
		    service.addBuddy("sip:" + csipUri);
		}
	    }});
        }
        addedAccounts.add(acc);
    }
    
    /**
     * Delete buddies for a given account
     * @param acc
     */
    private synchronized void deleteBuddiesForAccount(SipProfile acc) {
        // Get buddies uris for this account
        final List<String> toDel = getBuddiesForAccount(acc);

        if (toDel.size() > 0 && service != null) {
            for (String csipUri : toDel) {
                ContactsWrapper.getInstance().updateCSipPresence(service, csipUri, SipManager.PresenceStatus.UNKNOWN, "");
            }
            
            service.getExecutor().execute(new SipRunnable() {

                @Override
                protected void doRun() throws SameThreadException {
                    if(service != null) {
                        for (String csipUri : toDel) {
                            service.removeBuddy("sip:" + csipUri);
                        }
                    }
                }
            });
        }
        // Find the correct account to remove
        int toRemoveIndex = -1;
        for(int idx = 0; idx < addedAccounts.size(); idx++) {
            SipProfile existingAcc = addedAccounts.get(idx);
            if(existingAcc.id == acc.id) {
                toRemoveIndex = idx;
                break;
            }
        }
        
        if(toRemoveIndex >= 0) {
            addedAccounts.remove(toRemoveIndex);
        }
    }

    /**
     * Update internal state of registered account
     * Push buddies for registered account
     * Remove buddies for offline accounts
     */
    private synchronized void updateRegistrations() {
        if(service == null) {
            // Nothing to do at this point
            return;
        }
        Cursor c = service.getContentResolver().query(SipProfile.ACCOUNT_URI, ACC_PROJECTION,
                SipProfile.FIELD_ACTIVE + "=?", new String[] { "1" }, null);

        ArrayList<SipProfile> accToAdd = new ArrayList<SipProfile>();
        ArrayList<SipProfile> accToRemove = new ArrayList<SipProfile>();
        ArrayList<Long> alreadyAddedAcc = new ArrayList<Long>();
        for (SipProfile addedAcc : addedAccounts) {
            alreadyAddedAcc.add(addedAcc.id);
        }
        // Decide which accounts should be removed, added, left unchanged
        if (c != null && c.getCount() > 0) {
            try {
                if (c.moveToFirst()) {
                    do {
                        final SipProfile acc = new SipProfile(c);

                        AccountStatusDisplay accountStatusDisplay = AccountListUtils
                                .getAccountDisplay(service, acc.id);
                        if (accountStatusDisplay.availableForCalls) {
                            if (!alreadyAddedAcc.contains(acc.id)) {
                                accToAdd.add(acc);
                            }
                        } else {
                            if (alreadyAddedAcc.contains(acc.id)) {
                                accToRemove.add(acc);
                            }
                        }
                    } while (c.moveToNext());
                }
            } catch (Exception e) {
                Log.e(THIS_FILE, "Error on looping over sip profiles", e);
            } finally {
                c.close();
            }
        } else if(c != null) {
            c.close();
        }

        for (SipProfile acc : accToRemove) {
            deleteBuddiesForAccount(acc);
        }

        for (SipProfile acc : accToAdd) {
            addBuddiesForAccount(acc);
        }
    }
    private synchronized void updateBuddiesStatus() {
        if (service == null) {
            // Nothing to do at this point
            return;
        }

	Log.d(THIS_FILE, "buddies status changed");

	/*
	final Cursor c = service.getContentResolver().query(SipProfile.BUDDY_STATUS_URI, BUD_PROJECTION, null, null, null);
	if (c == null) return;
	service.getExecutor().execute(new SipRunnable() { @Override protected void doRun() throws SameThreadException {
	    if (c.moveToFirst()) {
		do {
		    final ContentValues v = new ContentValues();
		    DatabaseUtils.cursorRowToContentValues(c, v);
		    final String s = v.getAsString(SipProfile.FIELD_URI);
		    service.addBuddy("sip:" + s);
		    Log.d(THIS_FILE, "updateBuddiesStatus: add: "+s);
		} while (c.moveToNext());
	    }
	    c.close();
	}});
	*/
    }

    /**
     * Observer for changes of account registration status
     */
    class AccountStatusContentObserver extends ContentObserver {

        public AccountStatusContentObserver(Handler h) {
            super(h);
        }

        public void onChange(boolean selfChange) {
            updateRegistrations();
        }
    }

    class BuddyStatusContentObserver extends ContentObserver {
        public BuddyStatusContentObserver(Handler h) { super(h); }
        public void onChange(boolean selfChange) {
            updateBuddiesStatus();
        }
    }
    
    /**
     * Forward status change for a buddy to manager
     * @param buddyUri buddy uri 
     * @param monitorPres whether the status is currently monitored
     * @param presStatus the status 
     * @param statusText the text representing this status
     */
    public void changeBuddyState(int buddyId, String buddyUri, int monitorPres, SipManager.PresenceStatus presStatus, String statusText) {
	Log.d(THIS_FILE, buddyUri+", buddyId="+buddyId+", monitorPres=" + monitorPres + ", resStatus = " + presStatus + ", status = "+statusText);
        if (service != null) {
	    try {
		ContactsWrapper.getInstance().updateCSipPresence(service, buddyUri.replace("sip:", ""), presStatus, statusText);
		BuddyState bs = new BuddyState();
		String s = buddyUri.replace("sip:", "");
		bs.id = buddyId;
		bs.uri = buddyUri;
		bs.contact = s.substring(0, s.indexOf("@"));
		bs.status = presStatus.ordinal();
		bs.status_text = statusText;
		bs.monitor_pres = monitorPres != 0;
		service.getContentResolver().update(SipProfile.BUDDY_STATUS_URI, bs.getAsValues(), null, null);
	    } catch (Exception e) {
		Log.e(THIS_FILE, "can't update status", e);
	    }
        }
    }
}
