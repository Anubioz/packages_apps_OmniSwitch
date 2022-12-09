/*
 *  Copyright (C) 2018 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.omnirom.omniswitch.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import org.omnirom.omniswitch.IEditFavoriteActivity;
import org.omnirom.omniswitch.PackageManager;
import org.omnirom.omniswitch.PackageManager.PackageItem;
import org.omnirom.omniswitch.R;
import org.omnirom.omniswitch.SwitchConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import androidx.appcompat.app.AlertDialog;

public class HiddenAppsDialog extends AlertDialog implements
        DialogInterface.OnClickListener {

    private PackageAdapter mPackageAdapter;
    private ListView mListView;
    private List<PackageItem> mInstalledPackages;
    private IEditFavoriteActivity mEditor;
    private Collection<String> mHiddenAppsList;
    private SwitchConfiguration mConfiguration;

    private class ViewHolder {
        TextView item;
        Switch check;
        ImageView image;
    }

    private class PackageAdapter extends BaseAdapter {

        private void reloadList() {
            mInstalledPackages = new ArrayList<PackageItem>();
            mInstalledPackages.addAll(PackageManager.getInstance(getContext()).getPackageList());
            Collections.sort(mInstalledPackages);
        }

        public PackageAdapter() {
            reloadList();
        }

        @Override
        public int getCount() {
            return mInstalledPackages.size();
        }

        @Override
        public PackageItem getItem(int position) {
            return mInstalledPackages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return mInstalledPackages.get(position).getPackageName().hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView != null) {
                holder = (ViewHolder) convertView.getTag();
            } else {
                convertView = getLayoutInflater().inflate(
                        R.layout.installed_app_item, parent, false);
                holder = new ViewHolder();
                convertView.setTag(holder);

                holder.item = (TextView) convertView
                        .findViewById(R.id.app_item);
                holder.check = (Switch) convertView
                        .findViewById(R.id.app_check);
                holder.image = (ImageView) convertView
                        .findViewById(R.id.app_icon);
            }
            PackageItem applicationInfo = getItem(position);
            holder.item.setText(applicationInfo.getTitle());
            Drawable d = BitmapCache.getInstance(getContext())
                    .getPackageIconCached(getContext().getResources(), applicationInfo,
                            mConfiguration);
            holder.image.setImageDrawable(d.mutate());
            holder.check.setChecked(mHiddenAppsList
                    .contains(applicationInfo.getPackageName()));

            return convertView;
        }
    }

    public HiddenAppsDialog(Context context, IEditFavoriteActivity editor, Collection<String> hiddenAppsList) {
        super(context, R.style.AlertDialogTheme);
        mHiddenAppsList = hiddenAppsList;
        mEditor = editor;
        mConfiguration = SwitchConfiguration.getInstance(context);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            mEditor.applyHiddenAppsChanges(mHiddenAppsList);
        } else if (which == DialogInterface.BUTTON_NEGATIVE) {
            cancel();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        final Context context = getContext();
        final View view = getLayoutInflater().inflate(
                R.layout.installed_apps_dialog, null);
        setView(view);
        setTitle(R.string.hidden_apps_dialog_title);
        setCancelable(true);

        setButton(DialogInterface.BUTTON_POSITIVE,
                context.getString(android.R.string.ok), this);
        setButton(DialogInterface.BUTTON_NEGATIVE,
                context.getString(android.R.string.cancel), this);

        super.onCreate(savedInstanceState);

        mListView = (ListView) view.findViewById(R.id.installed_apps);
        mPackageAdapter = new PackageAdapter();
        mListView.setAdapter(mPackageAdapter);
        mListView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                                    int position, long id) {
                PackageItem info = (PackageItem) parent
                        .getItemAtPosition(position);
                ViewHolder viewHolder = (ViewHolder) view.getTag();
                viewHolder.check.setChecked(!viewHolder.check.isChecked());
                if (viewHolder.check.isChecked()) {
                    if (!mHiddenAppsList.contains(info.getPackageName())) {
                        mHiddenAppsList.add(info.getPackageName());
                    }
                } else {
                    mHiddenAppsList.remove(info.getPackageName());
                }
            }
        });
    }
}

