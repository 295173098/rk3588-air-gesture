package com.rk3588.airgesture;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.fragment.app.FragmentActivity;

import com.mine.bean.PermissionBean;
import com.mine.ui.PermissionFragment;
import com.mine.xpermission.PermissionManager;

import java.util.ArrayList;
import java.util.List;

public class StartActivity extends FragmentActivity {

    private PermissionManager permissionManager;

    @Override
    protected void onCreate(Bundle paramBundle) {
        super.onCreate(paramBundle);
        setContentView(R.layout.activity_start);

    }


    public void checkVideoPermission() {
        List<PermissionBean> list = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            list.add(new PermissionBean(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION));
        }
        list.add(new PermissionBean(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(new PermissionBean(Manifest.permission.READ_MEDIA_AUDIO));
            list.add(new PermissionBean(Manifest.permission.READ_MEDIA_IMAGES));
            list.add(new PermissionBean(Manifest.permission.READ_MEDIA_VIDEO));
        } else {
            list.add(new PermissionBean(Manifest.permission.READ_EXTERNAL_STORAGE));
            list.add(new PermissionBean(Manifest.permission.WRITE_EXTERNAL_STORAGE));
        }
        list.add(new PermissionBean(Manifest.permission.CAMERA));
        if (permissionManager == null) {
            permissionManager = new PermissionManager(StartActivity.this);
        }
        boolean isComplete = permissionManager.checkAllPermissionComplete(list);
        if (isComplete) {
            startGoToView();
            return;
        }
        PermissionFragment permissionFragment = new PermissionFragment();
        permissionFragment.setPermissionList(list, getString(com.mine.xpermission.R.string.permission_title), "进入APP");
        permissionFragment.setBoardRoot(true);
        permissionFragment.setOnCallBackListener(() -> startGoToView());
        permissionFragment.show(getSupportFragmentManager(), "PermissionFragment");
    }

    private void startGoToView() {
        startActivity(new Intent(StartActivity.this, MainActivity.class));
        finish();
    }


}
