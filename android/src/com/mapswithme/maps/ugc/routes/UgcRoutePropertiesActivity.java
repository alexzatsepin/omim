package com.mapswithme.maps.ugc.routes;

import androidx.fragment.app.Fragment;

import com.mapswithme.maps.base.BaseToolbarActivity;

public class UgcRoutePropertiesActivity extends BaseToolbarActivity
{
  @Override
  protected Class<? extends Fragment> getFragmentClass()
  {
    return UgcRoutePropertiesFragment.class;
  }
}
