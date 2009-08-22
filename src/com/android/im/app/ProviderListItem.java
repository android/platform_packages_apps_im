/*
 * Copyright (C) 2009 Myriad Group AG
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.im.app;

import com.android.im.R;

import android.graphics.drawable.Drawable;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.content.Context;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.database.Cursor;
import android.im.BrandingResourceIDs;
import android.content.res.ColorStateList;
import android.view.View;
import android.provider.Im;
import android.util.Log;

public class ProviderListItem extends LinearLayout {
    private static final String TAG = "IM";
    private static final boolean LOCAL_DEBUG = false;

    private LandingPage mActivity;
    private ImageView mProviderIcon;
    private ImageView mStatusIcon;
    private TextView mProviderName;
    private TextView mLoginName;
    private TextView mChatView;
    private View mUnderBubble;
    private Drawable mBubbleDrawable, mDefaultBackground;

    private int mProviderIdColumn;
    private int mProviderFullnameColumn;
    private int mActiveAccountIdColumn;
    private int mActiveAccountUserNameColumn;
    private int mAccountPresenceStatusColumn;
    private int mAccountConnectionStatusColumn;

    private ColorStateList mProviderNameColors;
    private ColorStateList mLoginNameColors;
    private ColorStateList mChatViewColors;

    public ProviderListItem(Context context, LandingPage activity) {
        super(context);
        mActivity = activity;
    }

    public void init(Cursor c) {
        mProviderIcon = (ImageView) findViewById(R.id.providerIcon);
        mStatusIcon = (ImageView) findViewById(R.id.statusIcon);
        mProviderName = (TextView) findViewById(R.id.providerName);
        mLoginName = (TextView) findViewById(R.id.loginName);
        mChatView = (TextView) findViewById(R.id.conversations);
        mUnderBubble = findViewById(R.id.underBubble);
        mBubbleDrawable = getResources().getDrawable(R.drawable.bubble);
        mDefaultBackground = getResources().getDrawable(R.drawable.default_background);

        mProviderIdColumn = c.getColumnIndexOrThrow(Im.Provider._ID);
        mProviderFullnameColumn = c.getColumnIndexOrThrow(Im.Provider.FULLNAME);
        mActiveAccountIdColumn = c.getColumnIndexOrThrow(
                Im.Provider.ACTIVE_ACCOUNT_ID);
        mActiveAccountUserNameColumn = c.getColumnIndexOrThrow(
                Im.Provider.ACTIVE_ACCOUNT_USERNAME);
        mAccountPresenceStatusColumn = c.getColumnIndexOrThrow(
                Im.Provider.ACCOUNT_PRESENCE_STATUS);
        mAccountConnectionStatusColumn = c.getColumnIndexOrThrow(
                Im.Provider.ACCOUNT_CONNECTION_STATUS);

        mProviderNameColors = mProviderName.getTextColors();
        mLoginNameColors = mLoginName.getTextColors();
        mChatViewColors = mChatView.getTextColors();
    }

    public void bindView(Cursor cursor) {
        Resources r = getResources();
        ImageView providerIcon = mProviderIcon;
        ImageView statusIcon = mStatusIcon;
        TextView providerName = mProviderName;
        TextView loginName = mLoginName;
        TextView chatView = mChatView;

        int providerId = cursor.getInt(mProviderIdColumn);
        String providerDisplayName = cursor.getString(mProviderFullnameColumn);

        ImApp app = ImApp.getApplication(mActivity);
        BrandingResources brandingRes = app.getBrandingResource(providerId);
        providerIcon.setImageDrawable(
                brandingRes.getDrawable(BrandingResourceIDs.DRAWABLE_LOGO));

        mUnderBubble.setBackgroundDrawable(mDefaultBackground);
        statusIcon.setVisibility(View.GONE);

        providerName.setTextColor(mProviderNameColors);
        loginName.setTextColor(mLoginNameColors);
        chatView.setTextColor(mChatViewColors);

        if (!cursor.isNull(mActiveAccountIdColumn)) {
            mLoginName.setVisibility(View.VISIBLE);
            providerName.setVisibility(View.VISIBLE);
            providerName.setText(providerDisplayName);

            long accountId = cursor.getLong(mActiveAccountIdColumn);
            int connectionStatus = cursor.getInt(mAccountConnectionStatusColumn);

            String secondRowText;

            chatView.setVisibility(View.GONE);

            switch (connectionStatus) {
                case Im.ConnectionStatus.CONNECTING:
                    secondRowText = r.getString(R.string.signing_in_wait);
                    break;

                case Im.ConnectionStatus.ONLINE:
                    int presenceIconId = getPresenceIconId(cursor);
                    statusIcon.setImageDrawable(
                            brandingRes.getDrawable(presenceIconId));
                    statusIcon.setVisibility(View.VISIBLE);
                    ContentResolver cr = mActivity.getContentResolver();

                    int count = getConversationCount(cr, accountId);
                    if (count > 0) {
                        mUnderBubble.setBackgroundDrawable(mBubbleDrawable);
                        chatView.setVisibility(View.VISIBLE);
                        chatView.setText(r.getString(R.string.conversations, count));

                        providerName.setTextColor(0xff000000);
                        loginName.setTextColor(0xff000000);
                        chatView.setTextColor(0xff000000);
                    } else {
                        chatView.setVisibility(View.GONE);
                    }

                    secondRowText = cursor.getString(mActiveAccountUserNameColumn);
                    break;

                default:
                    secondRowText = cursor.getString(mActiveAccountUserNameColumn);
                    break;
            }

            loginName.setText(secondRowText);

        } else {
            // No active account, show add account
            mLoginName.setVisibility(View.GONE);
            mChatView.setVisibility(View.GONE);
            mProviderName.setText(providerDisplayName);
        }
    }

    private int getConversationCount(ContentResolver cr, long accountId) {
        // TODO: this is code used to get Google Talk's chat count. Not sure if this will work
        // for IMPS chat count.
        StringBuilder where = new StringBuilder();
        where.append(Im.Chats.CONTACT_ID);
        where.append(" in (select _id from contacts where ");
        where.append(Im.Contacts.ACCOUNT);
        where.append("=");
        where.append(accountId);
        where.append(")");

        Cursor cursor = cr.query(Im.Chats.CONTENT_URI, null, where.toString(), null, null);

        try {
            return cursor.getCount();
        } finally {
            cursor.close();
        }
    }

    private int getPresenceIconId(Cursor cursor) {
        int presenceStatus = cursor.getInt(mAccountPresenceStatusColumn);

        if (LOCAL_DEBUG) log("getPresenceIconId: presenceStatus=" + presenceStatus);

        switch (presenceStatus) {
            case Im.Presence.AVAILABLE:
                return BrandingResourceIDs.DRAWABLE_PRESENCE_ONLINE;

            case Im.Presence.IDLE:
            case Im.Presence.AWAY:
                return BrandingResourceIDs.DRAWABLE_PRESENCE_AWAY;

            case Im.Presence.DO_NOT_DISTURB:
                return BrandingResourceIDs.DRAWABLE_PRESENCE_BUSY;

            case Im.Presence.INVISIBLE:
                return BrandingResourceIDs.DRAWABLE_PRESENCE_INVISIBLE;

            default:
                return BrandingResourceIDs.DRAWABLE_PRESENCE_OFFLINE;
        }
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }
}