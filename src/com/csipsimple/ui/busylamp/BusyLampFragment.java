/**
 * Copyright (C) 2013 Duzy Chan <code@duzy.info>
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


package com.csipsimple.ui.busylamp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Resources;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri.Builder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.text.TextUtils;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.CheckBox;

import com.actionbarsherlock.app.SherlockListFragment;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.actionbarsherlock.view.MenuItem.OnMenuItemClickListener;
import com.csipsimple.R;
import com.csipsimple.api.ISipService;
import com.csipsimple.api.SipProfile;
import com.csipsimple.api.SipUri;
import com.csipsimple.api.BuddyState;
import com.csipsimple.models.CallerInfo;
import com.csipsimple.service.SipNotifications;
import com.csipsimple.service.SipService;
import com.csipsimple.ui.PickupSipUri;
import com.csipsimple.ui.SipHome.ViewPagerVisibilityListener;
import com.csipsimple.utils.Log;
import com.csipsimple.utils.clipboard.ClipboardWrapper;
import com.csipsimple.utils.contacts.ContactsWrapper;
import com.csipsimple.widgets.AccountChooserButton;
import com.csipsimple.widgets.CSSListFragment;

public class BusyLampFragment extends SherlockListFragment
    implements LoaderManager.LoaderCallbacks<Cursor>, ViewPagerVisibilityListener, OnClickListener
{
    private static final String THIS_FILE = "BusyLamp";
    private AccountStatusContentObserver mAccountStatusContentObserver;
    private BuddyStatusContentObserver mBuddyStatusContentObserver;
    private BusyLampAdapter mAdapter;
    private View mProgressContainer;
    private View mListContainer;
    private Handler mHandler = new Handler();
    private boolean mAccountStatusChanged = false;

    //private ClipboardWrapper clipboardManager;

    class AccountStatusContentObserver extends ContentObserver {
        public AccountStatusContentObserver(Handler h) { super(h); }
        public void onChange(boolean selfChange) {
            onAccountStatusChanged();
        }
    }

    class BuddyStatusContentObserver extends ContentObserver {
        public BuddyStatusContentObserver(Handler h) { super(h); }
        public void onChange(boolean selfChange) {
	    onBuddyStateChanged();
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setHasOptionsMenu(true);

        final ListView lv = getListView();
        lv.setOnCreateContextMenuListener(this);
	lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
		public boolean onItemLongClick(AdapterView<?> arg0, View view, int index, long arg3) {
		    final Cursor c = (Cursor) arg0.getItemAtPosition(index);
		    final ContentValues v = new ContentValues();
		    DatabaseUtils.cursorRowToContentValues(c, v);
		    Log.d(THIS_FILE, ""+v);
		    showActionsOnBuddy(v);
		    return true;
		}
	    }); 
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //clipboardManager = ClipboardWrapper.getInstance(getActivity());

	if (mAccountStatusContentObserver == null) {
	    mAccountStatusContentObserver = new AccountStatusContentObserver(mHandler);
	    getActivity().getContentResolver().registerContentObserver(SipProfile.ACCOUNT_STATUS_URI.buildUpon().appendPath(SipProfile.FIELD_SELECTED).build(), true, mAccountStatusContentObserver);
	}
	if (mBuddyStatusContentObserver == null) {
	    mBuddyStatusContentObserver = new BuddyStatusContentObserver(mHandler);
	    getActivity().getContentResolver().registerContentObserver(SipProfile.BUDDY_STATUS_URI, true, mBuddyStatusContentObserver);
	    getActivity().getContentResolver().registerContentObserver(SipProfile.BUDDY_ID_URI_BASE, true, mBuddyStatusContentObserver);
	}
    }
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.busylamp_list_fragment, container, false);
        return v;
    }
    
    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        getListView().setDivider(null);

	mProgressContainer = view.findViewById(R.id.progressContainer);
	mListContainer = view.findViewById(R.id.listContainer);

	if (mAdapter == null) {
	    mAdapter = new BusyLampAdapter(getActivity(), null);
	    //getListView().setAdapter(mAdapter);
	}

	mAccountStatusChanged = false;
	getLoaderManager().initLoader(0, null, this);
    }
    
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SipService.class), connection, Context.BIND_AUTO_CREATE);
    }
    
    @Override
    public void onDetach() {
        try {
            getActivity().unbindService(connection);
        } catch (Exception e) {
            // Just ignore that
        }
        service = null;
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    private final static int PICKUP_SIP_URI = 0;
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
    }

    // Service connection
    private ISipService service;
    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName arg0, IBinder arg1) {
            service = ISipService.Stub.asInterface(arg1);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            service = null;
        }
    };
    
    @Override
    public void onClick(View v) {
        final int vid = v.getId();
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        Log.d(THIS_FILE, "onCreateLoader: "+id+", "+args);
        Builder builder = SipProfile.BUDDY_URI.buildUpon().appendPath(SipProfile.FIELD_SELECTED);
        return new CursorLoader(getActivity(), builder.build(), null, null, null, SipProfile.FIELD_DISPLAY_NAME + " ASC");
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        Log.d(THIS_FILE, "onLoadFinished: buddies: "+data.getCount());

	final Cursor c = getActivity().getContentResolver().query(SipProfile.BUDDY_STATUS_URI, null, null, null, null);
	if (c != null) {
	    if (c.moveToFirst()) do {
		final ContentValues v = new ContentValues();
		DatabaseUtils.cursorRowToContentValues(c, v);
		BuddyState bs = new BuddyState();
		bs.createFromValues(v);
		mAdapter.setStatus(v.getAsLong(BuddyState.BUDDY_ID), bs);
		Log.d(THIS_FILE, "status: "+v);
	    } while (c.moveToNext());
	    c.close();
	} else {
	    Log.d(THIS_FILE, "onLoadFinished: no buddy status");
	}

        mAdapter.swapCursor(data);
	getListView().setAdapter(mAdapter);
	if (data.getCount() == 0 && !mAccountStatusChanged) {
	    mProgressContainer.setVisibility(View.VISIBLE);
	    mListContainer.setVisibility(View.GONE);
	} else {
	    mProgressContainer.setVisibility(View.GONE);
	    mListContainer.setVisibility(View.VISIBLE);
	}
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
    }

    // Options
    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);

        int actionRoom = getResources().getBoolean(R.bool.menu_in_bar) ? MenuItem.SHOW_AS_ACTION_IF_ROOM : MenuItem.SHOW_AS_ACTION_NEVER;
        MenuItem addContactMenu = menu.add(R.string.menu_add_to_contacts);
        addContactMenu.setIcon(R.drawable.ic_add_contact_holo_dark).setShowAsAction(actionRoom);
        addContactMenu.setOnMenuItemClickListener(new OnMenuItemClickListener() { @Override public boolean onMenuItemClick(MenuItem item) {
	    startEditBuddy(null);
	    return true;
	}});
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
    }

    // Context menu
    public static final int MENU_COPY = ContextMenu.FIRST;

    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        menu.add(0, MENU_COPY, 0, R.string.copy_message_text);
    }
    
    @Override
    public boolean onContextItemSelected(android.view.MenuItem item) {
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        Cursor c = (Cursor) mAdapter.getItem(info.position);
        if (c != null) {
	    // ...
        }
        return super.onContextItemSelected(item);
    }

    private void onAccountStatusChanged() {
        Log.d(THIS_FILE, "onAccountStatusChanged");
	try {
	    getLoaderManager().restartLoader(0, null, BusyLampFragment.this);
	} catch (Exception e) {
	    Log.e(THIS_FILE, "onAccountStatusChanged", e);
	}
    }

    private void onBuddyStateChanged() {
        Log.d(THIS_FILE, "onBuddyStateChanged");
	try {
	    getLoaderManager().restartLoader(0, null, BusyLampFragment.this);
	} catch (Exception e) {
	    Log.e(THIS_FILE, "onBuddyStateChanged", e);
	}
    }

    private void showActionsOnBuddy(final ContentValues v) {
	final ListView view = new ListView(getActivity());
	final Resources res = getActivity().getResources();
	final ArrayAdapter<String> adapter = new ArrayAdapter<String>(getActivity(), android.R.layout.simple_list_item_1);
	final AlertDialog d = new AlertDialog.Builder(getActivity()).setView(view).create();
	adapter.add(res.getString(R.string.edit));
	adapter.add(res.getString(R.string.delete));
	view.setOnItemClickListener(new AdapterView.OnItemClickListener() {
		public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
		    switch (position) {
		    case 0: startEditBuddy(v);	break;
		    case 1: deleteBuddy(v);	break;
		    }
		    d.dismiss();
		}
	    });
	view.setAdapter(adapter);
	d.show();
    }

    private void startEditBuddy(final ContentValues v) {
	final View view = LayoutInflater.from(getActivity()).inflate(R.layout.buddy_edit_dialog, null, false);
	final TextView number = (TextView) view.findViewById(R.id.buddy_number);
	final TextView name = (TextView) view.findViewById(R.id.display_name);
	final CheckBox subscribe = (CheckBox) view.findViewById(R.id.subscribe);
	AlertDialog.OnClickListener confirmListener;
	int confirmButtonLabel = R.string.add;
	if (v != null) {
	    final long id = v.getAsLong(SipProfile._ID);
	    number.setText(v.getAsString(SipProfile.FIELD_CONTACT));
	    name.setText(v.getAsString(SipProfile.FIELD_DISPLAY_NAME));
	    subscribe.setChecked(v.getAsInteger(SipProfile.FIELD_SUBSCRIBE) != 0);
	    confirmButtonLabel = R.string.save;
	    confirmListener = new AlertDialog.OnClickListener() { public void onClick(DialogInterface d, int i) {
		saveBuddy(id, number.getText().toString().trim(), name.getText().toString().trim(), subscribe.isChecked());
	    }};
	} else {
	    confirmListener = new AlertDialog.OnClickListener() { public void onClick(DialogInterface d, int i) {
		addNewBuddy(number.getText().toString().trim(), name.getText().toString().trim(), subscribe.isChecked());
	    }};
	}
	new AlertDialog.Builder(getActivity()).setView(view)
	    .setNegativeButton(R.string.cancel, null)
	    .setPositiveButton(confirmButtonLabel, confirmListener)
	    .show();
    }

    private void addNewBuddy(String number, String name, boolean subscribe) {
	if (service == null) return;
	try {
	    ContentValues values = new ContentValues();
	    values.put(SipProfile.FIELD_CONTACT, number);
	    values.put(SipProfile.FIELD_DISPLAY_NAME, name);
	    values.put(SipProfile.FIELD_SUBSCRIBE, subscribe);
	    // No ACCOUNT field for values!
	    getActivity().getContentResolver().insert(SipProfile.BUDDY_URI, values);
	} catch (Exception e) {
	    Log.e(THIS_FILE, "can't add new buddy", e);
	}
    }

    private void saveBuddy(long id, String number, String name, boolean subscribe) {
	try {
	    ContentValues values = new ContentValues();
	    values.put(SipProfile.FIELD_CONTACT, number);
	    values.put(SipProfile.FIELD_DISPLAY_NAME, name);
	    values.put(SipProfile.FIELD_SUBSCRIBE, subscribe);
	    // No ACCOUNT field for values!
	    getActivity().getContentResolver().update(ContentUris.withAppendedId(SipProfile.BUDDY_ID_URI_BASE, id), values, null, null);
	} catch (Exception e) {
	    Log.e(THIS_FILE, "can't update buddy", e);
	}
    }

    private void deleteBuddy(final ContentValues v) {
	try {
	    final long id = v.getAsLong(SipProfile._ID);
	    getActivity().getContentResolver().delete(ContentUris.withAppendedId(SipProfile.BUDDY_ID_URI_BASE, id), null, null);
	} catch (Exception e) {
	    Log.e(THIS_FILE, "can't delete buddy", e);
	}
    }
}
