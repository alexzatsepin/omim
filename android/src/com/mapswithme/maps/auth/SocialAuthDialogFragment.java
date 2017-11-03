package com.mapswithme.maps.auth;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import com.facebook.AccessToken;
import com.facebook.CallbackManager;
import com.facebook.FacebookCallback;
import com.facebook.FacebookException;
import com.facebook.login.LoginResult;
import com.facebook.login.widget.LoginButton;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.GoogleApiClient;
import com.mapswithme.maps.Framework;
import com.mapswithme.maps.R;
import com.mapswithme.maps.base.BaseMwmDialogFragment;
import com.mapswithme.util.log.Logger;
import com.mapswithme.util.log.LoggerFactory;
import com.mapswithme.util.statistics.Statistics;

import java.lang.ref.WeakReference;

public class SocialAuthDialogFragment extends BaseMwmDialogFragment
{
  private static final Logger LOGGER = LoggerFactory.INSTANCE.getLogger(LoggerFactory.Type.MISC);
  private static final String TAG = SocialAuthDialogFragment.class.getSimpleName();
  private static final int REQ_CODE_GOOGLE_SIGN_IN = 103;
  @NonNull
  private final CallbackManager mFBCallbackManager = CallbackManager.Factory.create();
  @SuppressWarnings("NullableProblems")
  @NonNull
  private GoogleApiClient mGoogleApiClient;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("1033279251462-k2pp753lp1f39dacjn0pkurcc8pbbvsi.apps.googleusercontent.com")
            .requestEmail()
            .build();
    mGoogleApiClient = new GoogleApiClient.Builder(getActivity())
            .enableAutoManage(getActivity(), new GoogleApiClient.OnConnectionFailedListener() {
              @Override
              public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

              }
            })
            .addApi(Auth.GOOGLE_SIGN_IN_API, gso)
            .build();
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState)
  {
    Dialog res = super.onCreateDialog(savedInstanceState);
    res.requestWindowFeature(Window.FEATURE_NO_TITLE);
    return res;
  }

  @Nullable
  @Override
  public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
  {
    View view = inflater.inflate(R.layout.fragment_auth_passport_dialog, container, false);
    LoginButton button = (LoginButton) view.findViewById(R.id.loging_button);
    button.setReadPermissions(Constants.FACEBOOK_PERMISSIONS);
    button.setFragment(this);
    button.registerCallback(mFBCallbackManager, new FBCallback(this));
    SignInButton signInButton = (SignInButton) view.findViewById(R.id.sign_in_button);
    signInButton.setSize(SignInButton.SIZE_STANDARD);
    signInButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        GoogleAuthUtil.getToken
        Intent signInIntent = Auth.GoogleSignInApi.getSignInIntent(mGoogleApiClient);
        startActivityForResult(signInIntent, REQ_CODE_GOOGLE_SIGN_IN);
      }
    });
    return view;
  }

  @Override
  public void onResume()
  {
    super.onResume();
    if (isGoogleTokenEmtpy() && isFBTokenEmpty())
    {
      Statistics.INSTANCE.trackEvent(Statistics.EventName.UGC_AUTH_SHOWN);
      return;
    }

    LOGGER.i(TAG, "Social token is already obtained");
    dismiss();
  }

  private boolean isGoogleTokenEmtpy()
  {
    return true;
  }

  private boolean isFBTokenEmpty()
  {
    AccessToken token = AccessToken.getCurrentAccessToken();
    String tokenValue = null;
    if (token != null)
      tokenValue = token.getToken();

    return TextUtils.isEmpty(tokenValue);
  }

  private void sendResult(int resultCode, @Nullable String socialToken,
                          @Framework.SocialTokenType int type)
  {
    Fragment caller = getTargetFragment();
    if (caller == null)
      return;

    Intent data = null;
    if (resultCode == Activity.RESULT_OK)
    {
      data = new Intent();
      data.putExtra(Constants.EXTRA_SOCIAL_TOKEN, socialToken);
      data.putExtra(Constants.EXTRA_TOKEN_TYPE, type);
    }
    caller.onActivityResult(Constants.REQ_CODE_GET_SOCIAL_TOKEN, resultCode, data);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data)
  {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQ_CODE_GOOGLE_SIGN_IN)
    {
      GoogleSignInResult result = Auth.GoogleSignInApi.getSignInResultFromIntent(data);
      handleGoogleSignInResult(result);
      return;
    }

    mFBCallbackManager.onActivityResult(requestCode, resultCode, data);
  }

  private void handleGoogleSignInResult(GoogleSignInResult result) {
    LOGGER.d(TAG, "handleGoogleSignInResult:" + result.isSuccess());
    if (result.isSuccess()) {
      // Signed in successfully, show authenticated UI.
      GoogleSignInAccount acct = result.getSignInAccount();
      LOGGER.d(TAG, "handleGoogleSignInResult token:" + acct.getIdToken());
      // TODO:
    } else {
      // Signed out, show unauthenticated UI.
      // TODO:
    }
  }

  @Override
  public void onDismiss(DialogInterface dialog)
  {
    Statistics.INSTANCE.trackEvent(Statistics.EventName.UGC_AUTH_DECLINED);
    AccessToken token = AccessToken.getCurrentAccessToken();
    sendResult(Activity.RESULT_OK, token != null ? token.getToken() : null,
               Framework.SOCIAL_TOKEN_FACEBOOK);
    super.onDismiss(dialog);
  }

  private static class FBCallback implements FacebookCallback<LoginResult>
  {
    @NonNull
    private final WeakReference<SocialAuthDialogFragment> mFragmentRef;

    private FBCallback(@NonNull SocialAuthDialogFragment fragment)
    {
      mFragmentRef = new WeakReference<>(fragment);
    }

    @Override
    public void onSuccess(LoginResult loginResult)
    {
      Statistics.INSTANCE.trackUGCExternalAuthSucceed(Statistics.ParamValue.FACEBOOK);
      LOGGER.d(TAG, "onSuccess");
    }

    @Override
    public void onCancel()
    {
      Statistics.INSTANCE.trackEvent(Statistics.EventName.UGC_AUTH_DECLINED);
      LOGGER.w(TAG, "onCancel");
      sendResult(Activity.RESULT_CANCELED, null, Framework.SOCIAL_TOKEN_FACEBOOK);
    }

    @Override
    public void onError(FacebookException error)
    {
      Statistics.INSTANCE.trackUGCAuthFailed(Statistics.ParamValue.FACEBOOK,
                                             error != null ? error.getMessage() : null);
      LOGGER.e(TAG, "onError", error);
      sendResult(Activity.RESULT_CANCELED, null, Framework.SOCIAL_TOKEN_FACEBOOK);
    }

    private void sendResult(int resultCode, @Nullable String socialToken,
                            @Framework.SocialTokenType int type)
    {
      SocialAuthDialogFragment fragment = mFragmentRef.get();
      if (fragment == null)
        return;

      fragment.sendResult(resultCode, socialToken, type);
    }
  }
}
