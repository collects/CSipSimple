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
 *  
 *  This file and this file only is also released under Apache license as an API file
 */

package com.csipsimple.api;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents state of the media <b>device</b> layer <br/>
 * This class helps to serialize/deserialize the state of the media layer <br/>
 * The fields it contains are direclty available. <br/>
 * <b>Changing these fields has no effect on the device audio</b> : it's only a
 * structured holder for datas <br/>
 * This class is not related to SIP media state this is managed by
 * {@link SipCallSession.MediaState}
 */
public class Buddy implements Parcelable 
{

    public int		id;
    public String	uri;
    public String 	contact;
    public int		status;
    public String	status_text;
    public boolean	monitor_pres;
    public int		sub_state;
    public String	sub_state_name;
    public int		sub_term_code;
    public String 	sub_term_reason;
    //pjrpid_element 	rpid;
    //pjsip_pres_status	pres_status;

    /**
     * Constructor for a buddy object <br/>
     */
    public Buddy() {
        // Nothing to do in default constructor
    }

    /**
     * Construct from parcelable <br/>
     * Only used by {@link #CREATOR}
     * 
     * @param in parcelable to build from
     */
    private Buddy(Parcel in) {
        id = in.readInt();
	uri = in.readString();
	contact = in.readString();
	status = in.readInt();
	status_text = in.readString();
	monitor_pres = (in.readInt() == 1);
	sub_state = in.readInt();
	sub_state_name = in.readString();
	sub_term_code = in.readInt();
	sub_term_reason = in.readString();
    }

    /**
     * Parcelable creator. So that it can be passed as an argument of the aidl
     * interface
     */
    public static final Parcelable.Creator<Buddy> CREATOR = new Parcelable.Creator<Buddy>() {
        public Buddy createFromParcel(Parcel in) {
            return new Buddy(in);
        }

        public Buddy[] newArray(int size) {
            return new Buddy[size];
        }
    };

    /**
     * @see Parcelable#describeContents()
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @see Parcelable#writeToParcel(Parcel, int)
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(id);
        dest.writeString(uri);
        dest.writeString(contact);
        dest.writeInt(status);
        dest.writeString(status_text);
        dest.writeInt(monitor_pres ? 1 : 0);
        dest.writeInt(sub_state);
        dest.writeString(sub_state_name);
        dest.writeInt(sub_term_code);
        dest.writeString(sub_term_reason);
    }
}
