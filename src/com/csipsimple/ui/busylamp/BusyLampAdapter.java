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

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.support.v4.widget.ResourceCursorAdapter;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.TextAppearanceSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.RelativeLayout.LayoutParams;
import android.widget.TextView;

import com.csipsimple.R;
import com.csipsimple.api.SipManager;
import com.csipsimple.api.SipMessage;
import com.csipsimple.api.SipProfile;
import com.csipsimple.models.CallerInfo;
import com.csipsimple.utils.ContactsAsyncHelper;
import com.csipsimple.utils.SmileyParser;
import com.csipsimple.widgets.contactbadge.QuickContactBadge;
import com.csipsimple.widgets.contactbadge.QuickContactBadge.ArrowPosition;

import java.text.SimpleDateFormat;
import java.util.Date;

public class BusyLampAdapter extends ResourceCursorAdapter
{
    private static SimpleDateFormat dateFormatter = new SimpleDateFormat("HH:mm:ss");
    
    public BusyLampAdapter(Context context, Cursor c) {
        super(context, R.layout.busylamp_list_item, c, 0);
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        View view = super.newView(context, cursor, parent);
        return view;
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        ContentValues values = new ContentValues();
        DatabaseUtils.cursorRowToContentValues(cursor, values);

	final ImageView statusLight = (ImageView) view.findViewById(R.id.status_light);
	final TextView contact = (TextView) view.findViewById(R.id.contact);
	final TextView name = (TextView) view.findViewById(R.id.name);
	boolean subscribe = values.getAsBoolean(SipProfile.FIELD_SUBSCRIBE);
	int status = -1;
	if (values.containsKey(SipProfile.FIELD_STATUS)) 
	    values.getAsInteger(SipProfile.FIELD_STATUS);
	name.setText(values.getAsString(SipProfile.FIELD_DISPLAY_NAME));
	contact.setText(values.getAsString(SipProfile.FIELD_CONTACT));
	if (SipManager.PresenceStatus.values().length < status) status = -1;
	if (status < 0) status = 0;
	statusLight.setTag(null);
	switch (SipManager.PresenceStatus.values()[status]) {
	case ONLINE:
	    statusLight.setImageResource(android.R.drawable.presence_online);
	    break;
	case INCOMING:
	    statusLight.setImageResource(android.R.drawable.presence_online);
	    statusLight.setTag(new Boolean(false));
	    statusLight.postDelayed(new Runnable() { public void run() {
		Boolean vis = (Boolean) statusLight.getTag();
		if (vis == null) {
		    statusLight.setVisibility(View.VISIBLE);
		    return;
		}
		statusLight.setVisibility(vis ? View.VISIBLE : View.GONE);
		statusLight.setTag(new Boolean(!vis));
		statusLight.postDelayed(this, 400);
	    }}, 500);
	    break;
	case BUSY:
	    statusLight.setImageResource(android.R.drawable.presence_busy);
	    break;
	case AWAY:
	    statusLight.setImageResource(android.R.drawable.presence_away);
	    break;
	case INVISIBLE:
	    statusLight.setImageResource(android.R.drawable.presence_invisible);
	    break;
	case OFFLINE:
	default:
	    statusLight.setImageResource(android.R.drawable.presence_offline);
	}
    }
}
