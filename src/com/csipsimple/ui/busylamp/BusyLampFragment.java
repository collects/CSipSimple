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
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri.Builder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
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
import com.csipsimple.models.CallerInfo;
import com.csipsimple.service.SipNotifications;
import com.csipsimple.service.SipService;
import com.csipsimple.ui.PickupSipUri;
import com.csipsimple.ui.SipHome.ViewPagerVisibilityListener;
import com.csipsimple.utils.Log;
import com.csipsimple.utils.SmileyParser;
import com.csipsimple.utils.clipboard.ClipboardWrapper;
import com.csipsimple.utils.contacts.ContactsWrapper;
import com.csipsimple.widgets.AccountChooserButton;
import com.csipsimple.widgets.CSSListFragment;

public class BusyLampFragment extends SherlockListFragment
    implements LoaderManager.LoaderCallbacks<Cursor>, ViewPagerVisibilityListener, OnClickListener
{
    private static final String THIS_FILE = "BusyLamp";
    private BusyLampAdapter mAdapter;
    private View mProgressContainer;
    private View mListContainer;

    public interface OnQuitListener {
        public void onQuit();
    }

    private OnQuitListener quitListener;
    private ClipboardWrapper clipboardManager;

    public void setOnQuitListener(OnQuitListener l) {
        quitListener = l;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setHasOptionsMenu(true);

        final ListView lv = getListView();
        lv.setOnCreateContextMenuListener(this);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SmileyParser.init(getActivity());
        clipboardManager = ClipboardWrapper.getInstance(getActivity());
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
	    getListView().setAdapter(mAdapter);
	}

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
        //notifications.setViewingMessageFrom(remoteFrom);
    }

    @Override
    public void onPause() {
        super.onPause();
        //notifications.setViewingMessageFrom(null);
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
        Log.d(THIS_FILE, "buddies: "+id+", "+args);
        Builder builder = SipProfile.BUDDY_URI.buildUpon(); //.appendEncodedPath(remoteFrom);
        return new CursorLoader(getActivity(), builder.build(), null, null, null, null/*SipProfile.FIELD_DATE + " ASC"*/);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        Log.d(THIS_FILE, "buddies: "+data.getCount());
        mAdapter.swapCursor(data);
	mProgressContainer.setVisibility(View.GONE);
	mListContainer.setVisibility(View.VISIBLE);
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
	    startAddBuddy();	    
	    return true;
	}});

	//getLoaderManager().restartLoader(0, null, BusyLampFragment.this);
    }

    boolean initLoaderCalled = false;
    @Override
    public void onVisibilityChanged(boolean visible) {
        if(visible) {
	    /*
	    if (getListAdapter() == null) {
		if(mAdapter == null) mAdapter = new BusyLampAdapter(getActivity(), null);
		setListAdapter(mAdapter);
		}
	    */
	    /*
	    if (mAdapter == null) {
		mAdapter = new BusyLampAdapter(getActivity(), null);
		getListView().setAdapter(mAdapter);
	    }
            if(!initLoaderCalled) {
                getLoaderManager().initLoader(0, null, this);
		initLoaderCalled = true;
	    }
	    */
        }
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

    private void startAddBuddy() {
	final View view = LayoutInflater.from(getActivity()).inflate(R.layout.buddy_edit_dialog, null, false);
	final TextView number = (TextView) view.findViewById(R.id.buddy_number);
	final TextView name = (TextView) view.findViewById(R.id.display_name);
	final CheckBox subscribe = (CheckBox) view.findViewById(R.id.subscribe);
	AlertDialog.OnClickListener confirmListener;
	int confirmButtonLabel = R.string.add;
	if (false) {
	    confirmButtonLabel = R.string.save;
	    confirmListener = new AlertDialog.OnClickListener() { public void onClick(DialogInterface d, int i) {
		saveBuddy(number.getText().toString().trim(), name.getText().toString().trim(), subscribe.isChecked());
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
	    getActivity().getContentResolver().insert(SipProfile.BUDDY_URI, values);
	} catch (Exception e) {
	    e.printStackTrace();
	}
    }

    private void saveBuddy(String number, String name, boolean subscribe) {
    }
}
