package com.reactnativegooglesignin;

import static com.reactnativegooglesignin.PromiseWrapper.ASYNC_OP_IN_PROGRESS;
import static com.reactnativegooglesignin.Utils.createScopesArray;
import static com.reactnativegooglesignin.Utils.getExceptionCode;
import static com.reactnativegooglesignin.Utils.getIdTokenRequestOptions;
import static com.reactnativegooglesignin.Utils.getSignInOptions;
import static com.reactnativegooglesignin.Utils.getUserProperties;
import static com.reactnativegooglesignin.Utils.scopesToString;

import android.accounts.Account;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.os.AsyncTask;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.auth.api.identity.BeginSignInRequest;
import com.google.android.gms.auth.api.identity.BeginSignInRequest.GoogleIdTokenRequestOptions;
import com.google.android.gms.auth.api.identity.BeginSignInResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.SignInClient;
import com.google.android.gms.auth.api.identity.SignInCredential;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;


@ReactModule(name = RNGoogleSigninModule.MODULE_NAME)
public class RNGoogleSigninModule extends ReactContextBaseJavaModule {
    private GoogleSignInClient _apiClient;

    private SignInClient _oneTapClient;

    private BeginSignInRequest signInRequest;

    public static final int RC_SIGN_IN = 9001;
    public static final int REQUEST_CODE_RECOVER_AUTH = 53294;
    public static final int REQUEST_CODE_ADD_SCOPES = 53295;

    public static final int ONE_TAP_SIGN_IN_SUCCESS = 9003;
    public static final int ONE_TAP_SIGN_UP_SUCCESS = 9003;

    public static final String MODULE_NAME = "RNGoogleSignin";
    public static final String PLAY_SERVICES_NOT_AVAILABLE = "PLAY_SERVICES_NOT_AVAILABLE";
    public static final String ERROR_USER_RECOVERABLE_AUTH = "ERROR_USER_RECOVERABLE_AUTH";
    private static final String SHOULD_RECOVER = "SHOULD_RECOVER";

    private boolean oneTapSignedIn;

    private PendingAuthRecovery pendingAuthRecovery;

    private PromiseWrapper promiseWrapper;

    public PromiseWrapper getPromiseWrapper() {
        return promiseWrapper;
    }

    @Override
    public String getName() {
        return MODULE_NAME;
    }

    public RNGoogleSigninModule(final ReactApplicationContext reactContext) {
        super(reactContext);
        promiseWrapper = new PromiseWrapper();
        reactContext.addActivityEventListener(new RNGoogleSigninActivityEventListener());
    }

    @Override
    public Map<String, Object> getConstants() {
        final Map<String, Object> constants = new HashMap<>();
        constants.put("BUTTON_SIZE_ICON", SignInButton.SIZE_ICON_ONLY);
        constants.put("BUTTON_SIZE_STANDARD", SignInButton.SIZE_STANDARD);
        constants.put("BUTTON_SIZE_WIDE", SignInButton.SIZE_WIDE);
        constants.put("BUTTON_COLOR_AUTO", SignInButton.COLOR_AUTO);
        constants.put("BUTTON_COLOR_LIGHT", SignInButton.COLOR_LIGHT);
        constants.put("BUTTON_COLOR_DARK", SignInButton.COLOR_DARK);
        constants.put("SIGN_IN_CANCELLED", String.valueOf(GoogleSignInStatusCodes.SIGN_IN_CANCELLED));
        constants.put("SIGN_IN_REQUIRED", String.valueOf(CommonStatusCodes.SIGN_IN_REQUIRED));
        constants.put("IN_PROGRESS", ASYNC_OP_IN_PROGRESS);
        constants.put(PLAY_SERVICES_NOT_AVAILABLE, PLAY_SERVICES_NOT_AVAILABLE);
        return constants;
    }

    @ReactMethod
    public void playServicesAvailable(boolean showPlayServicesUpdateDialog, Promise promise) {
        Activity activity = getCurrentActivity();

        if (activity == null) {
            Log.w(MODULE_NAME, "could not determine playServicesAvailable, activity is null");
            rejectWithNullActivity(promise);
            return;
        }

        GoogleApiAvailability googleApiAvailability = GoogleApiAvailability.getInstance();
        int status = googleApiAvailability.isGooglePlayServicesAvailable(activity);

        if (status != ConnectionResult.SUCCESS) {
            if (showPlayServicesUpdateDialog && googleApiAvailability.isUserResolvableError(status)) {
                int requestCode = 2404;
                googleApiAvailability.getErrorDialog(activity, status, requestCode).show();
            }
            promise.reject(PLAY_SERVICES_NOT_AVAILABLE, "Play services not available");
        } else {
            promise.resolve(true);
        }
    }

    private static void rejectWithNullActivity(Promise promise) {
        promise.reject(MODULE_NAME, "activity is null");
    }

    @ReactMethod
    public void configure(
            final ReadableMap config,
            final Promise promise
    ) {
        final ReadableArray scopes = config.hasKey("scopes") ? config.getArray("scopes") : Arguments.createArray();
        final String webClientId = config.hasKey("webClientId") ? config.getString("webClientId") : null;
        final boolean offlineAccess = config.hasKey("offlineAccess") && config.getBoolean("offlineAccess");
        final boolean forceCodeForRefreshToken = config.hasKey("forceCodeForRefreshToken") && config.getBoolean("forceCodeForRefreshToken");
        final String accountName = config.hasKey("accountName") ? config.getString("accountName") : null;
        final String hostedDomain = config.hasKey("hostedDomain") ? config.getString("hostedDomain") : null;

        GoogleSignInOptions options = getSignInOptions(createScopesArray(scopes), webClientId, offlineAccess, forceCodeForRefreshToken, accountName, hostedDomain);
        _apiClient = GoogleSignIn.getClient(getReactApplicationContext(), options);
        promise.resolve(null);
    }

    @ReactMethod
    public void configureOneTap(final ReadableMap config, final Promise promise) {
      final String webClientId = config.hasKey("webClientId") ? config.getString("webClientId") : null;
      final boolean filterByAuthorizedAccounts = config.hasKey("filterByAuthorizedAccounts") ? config.getBoolean("filterByAuthorizedAccounts") : true;
      final boolean autoSelect = config.hasKey("autoSelectEnabled") ? config.getBoolean("autoSelectEnabled") : true;

      _oneTapClient = Identity.getSignInClient(getReactApplicationContext());
      signInRequest = BeginSignInRequest.builder()
        .setGoogleIdTokenRequestOptions(
         getIdTokenRequestOptions(webClientId, filterByAuthorizedAccounts)
        ).setAutoSelectEnabled(autoSelect)
        .build();

      promise.resolve(null);
    }

    @ReactMethod
    public void oneTapSignIn(Promise promise) {
      promiseWrapper.setPromiseWithInProgressCheck(promise, "oneTap");

      handleOneTapRequest(promise, ONE_TAP_SIGN_IN_SUCCESS);
    }
    @ReactMethod
    public void oneTapSignUp(Promise promise) {
      promiseWrapper.setPromiseWithInProgressCheck(promise, "oneTap");

      handleOneTapRequest(promise, ONE_TAP_SIGN_UP_SUCCESS);
    }

    private void handleOneTapRequest(Promise promise, int code) {
      if (_oneTapClient == null) {
        rejectWithNullClientError(promise);
        return;
      }
      final Activity activity = getCurrentActivity();

      if (activity == null) {
        rejectWithNullActivity(promise);
        return;
      }
      UiThreadUtil.runOnUiThread(() -> _oneTapClient.beginSignIn(signInRequest).addOnSuccessListener(activity, result -> {
        try {
          activity.startIntentSenderForResult(
            result.getPendingIntent().getIntentSender(), code, null, 0,0,0);
        } catch (IntentSender.SendIntentException e) {
          Log.e(MODULE_NAME, "Couldn't start One Tap UI: " + e.getLocalizedMessage());
        }
      })
        .addOnFailureListener(activity, e -> Log.d(MODULE_NAME, "One Tap UI: Failure: " + e.getLocalizedMessage())));
    }

    @ReactMethod
    public void signInSilently(Promise promise) {
        if (_apiClient == null) {
            rejectWithNullClientError(promise);
            return;
        }
        promiseWrapper.setPromiseWithInProgressCheck(promise, "signInSilently");
        UiThreadUtil.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Task<GoogleSignInAccount> result = _apiClient.silentSignIn();
                if (result.isSuccessful()) {
                    // There's immediate result available.
                    handleSignInTaskResult(result);
                } else {
                    result.addOnCompleteListener(new OnCompleteListener() {
                        @Override
                        public void onComplete(@NonNull Task task) {
                            handleSignInTaskResult(task);
                        }
                    });
                }
            }
        });
    }

    private void handleSignInTaskResult(Task<GoogleSignInAccount> result) {
        try {
            GoogleSignInAccount account = result.getResult(ApiException.class);
            if (account == null) {
                promiseWrapper.reject(MODULE_NAME, "GoogleSignInAccount instance was null");
            } else {
                WritableMap userParams = getUserProperties(account);
                promiseWrapper.resolve(userParams);
            }
        } catch (ApiException e) {
            int code = e.getStatusCode();
            String errorDescription = GoogleSignInStatusCodes.getStatusCodeString(code);
            promiseWrapper.reject(String.valueOf(code), errorDescription);
        }
    }

    private void handleOneTapSignInResult(SignInCredential credential) {
      WritableMap userParams = getUserProperties(credential);
      promiseWrapper.resolve(userParams);
    }

    @ReactMethod
    public void signIn(final ReadableMap config, Promise promise) {
        if (_apiClient == null) {
            rejectWithNullClientError(promise);
            return;
        }

        final Activity activity = getCurrentActivity();

        if (activity == null) {
            rejectWithNullActivity(promise);
            return;
        }
        promiseWrapper.setPromiseWithInProgressCheck(promise, "signIn");
        UiThreadUtil.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent signInIntent = _apiClient.getSignInIntent();
                activity.startActivityForResult(signInIntent, RC_SIGN_IN);
            }
        });
    }

    @ReactMethod
    public void addScopes(final ReadableMap config, Promise promise) {
      final Activity activity = getCurrentActivity();
      if (activity == null) {
        rejectWithNullActivity(promise);
        return;
      }
      GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(getReactApplicationContext());
      if (account == null) {
        promise.resolve(false);
        return;
      }
      promiseWrapper.setPromiseWithInProgressCheck(promise, "addScopes");

      ReadableArray scopes = config.getArray("scopes");
      Scope[] scopeArr = new Scope[scopes.size()];
      for (int i = 0; i < scopes.size(); i++) {
        scopeArr[i] = new Scope(scopes.getString(i));
      }

      GoogleSignIn.requestPermissions(
        activity, REQUEST_CODE_ADD_SCOPES, account, scopeArr);
    }

    private class RNGoogleSigninActivityEventListener extends BaseActivityEventListener {
        @Override
        public void onActivityResult(Activity activity, final int requestCode, final int resultCode, final Intent intent) {
            if (requestCode == RC_SIGN_IN) {
                // The Task returned from this call is always completed, no need to attach a listener.
                Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(intent);
                handleSignInTaskResult(task);
            } else if (requestCode == REQUEST_CODE_RECOVER_AUTH) {
                if (resultCode == Activity.RESULT_OK) {
                    rerunFailedAuthTokenTask();
                } else {
                    promiseWrapper.reject(MODULE_NAME, "Failed authentication recovery attempt, probably user-rejected.");
                }
            } else if (requestCode == REQUEST_CODE_ADD_SCOPES) {
                if (resultCode == Activity.RESULT_OK) {
                  promiseWrapper.resolve(true);
                } else {
                  promiseWrapper.reject(MODULE_NAME, "Failed to add scopes.");
                }
            } else if (requestCode == ONE_TAP_SIGN_IN_SUCCESS) {
              try {
                SignInCredential credential = _oneTapClient.getSignInCredentialFromIntent(intent);
                oneTapSignedIn = true;
                handleOneTapSignInResult(credential);

              } catch (ApiException e) {
                Log.d(MODULE_NAME, "One Tap Failure: " + e.getLocalizedMessage());
                promiseWrapper.reject(MODULE_NAME, "Failed to retrieve a credential");
              }
            }
        }
    }

    private void rerunFailedAuthTokenTask() {
        WritableMap userProperties = pendingAuthRecovery.getUserProperties();
        if (userProperties != null) {
            new AccessTokenRetrievalTask(this).execute(userProperties, null);
        } else {
            // this is unlikely to happen, since we set the pendingRecovery in AccessTokenRetrievalTask
            promiseWrapper.reject(MODULE_NAME, "rerunFailedAuthTokenTask: recovery failed");
        }
    }

    @ReactMethod
    public void signOut(final Promise promise) {
        if (_apiClient == null && _oneTapClient == null) {
            rejectWithNullClientError(promise);
            return;
        }

        if (_apiClient == null) {
          _oneTapClient.signOut().addOnCompleteListener(result -> {
            handleSignOutOrRevokeAccessTask(result, promise);
          });
          return;
        }

        _apiClient.signOut()
                .addOnCompleteListener(new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull Task<Void> task) {
                        handleSignOutOrRevokeAccessTask(task, promise);
                    }
                });
    }

    private void handleSignOutOrRevokeAccessTask(@NonNull Task<Void> task, final Promise promise) {
        if (task.isSuccessful()) {
            promise.resolve(null);
        } else {
            int code = getExceptionCode(task);
            String errorDescription = GoogleSignInStatusCodes.getStatusCodeString(code);
            promise.reject(String.valueOf(code), errorDescription);
        }
    }

    @ReactMethod
    public void revokeAccess(final Promise promise) {
        if (_apiClient == null) {
            rejectWithNullClientError(promise);
            return;
        }

        _apiClient.revokeAccess()
                .addOnCompleteListener(new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull Task<Void> task) {
                        handleSignOutOrRevokeAccessTask(task, promise);
                    }
                });
    }

    @ReactMethod
    public void isSignedIn(Promise promise) {
      boolean isSignedIn = GoogleSignIn.getLastSignedInAccount(getReactApplicationContext()) != null || oneTapSignedIn;
      promise.resolve(isSignedIn);
    }

    @ReactMethod
    public void getCurrentUser(Promise promise) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(getReactApplicationContext());
        promise.resolve(account == null ? null : getUserProperties(account));
    }

    @ReactMethod
    public void clearCachedAccessToken(String tokenToClear, Promise promise) {
        promiseWrapper.setPromiseWithInProgressCheck(promise, "clearCachedAccessToken");
        new TokenClearingTask(this).execute(tokenToClear);
    }

    @ReactMethod
    public void getTokens(final Promise promise) {
        final GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(getReactApplicationContext());
        if (account == null) {
            promise.reject(MODULE_NAME, "getTokens requires a user to be signed in");
            return;
        }

        promiseWrapper.setPromiseWithInProgressCheck(promise, "getTokens");
        startTokenRetrievalTaskWithRecovery(account);
    }

    private void startTokenRetrievalTaskWithRecovery(GoogleSignInAccount account) {
        WritableMap userParams = getUserProperties(account);
        WritableMap recoveryParams = Arguments.createMap();
        recoveryParams.putBoolean(SHOULD_RECOVER, true);
        new AccessTokenRetrievalTask(this).execute(userParams, recoveryParams);
    }

    private static class AccessTokenRetrievalTask extends AsyncTask<WritableMap, Void, Void> {

        private WeakReference<RNGoogleSigninModule> weakModuleRef;

        AccessTokenRetrievalTask(RNGoogleSigninModule module) {
            this.weakModuleRef = new WeakReference<>(module);
        }

        @Override
        protected Void doInBackground(WritableMap... params) {
            WritableMap userProperties = params[0];
            final RNGoogleSigninModule moduleInstance = weakModuleRef.get();
            if (moduleInstance == null) {
                return null;
            }
            try {
                insertAccessTokenIntoUserProperties(moduleInstance, userProperties);
                moduleInstance.getPromiseWrapper().resolve(userProperties);
            } catch (Exception e) {
                WritableMap recoverySettings = params.length >= 2 ? params[1] : null;
                handleException(moduleInstance, e, userProperties, recoverySettings);
            }
            return null;
        }

        private void insertAccessTokenIntoUserProperties(RNGoogleSigninModule moduleInstance, WritableMap userProperties) throws IOException, GoogleAuthException {
            String mail = userProperties.getMap("user").getString("email");
            String token = GoogleAuthUtil.getToken(moduleInstance.getReactApplicationContext(),
                    new Account(mail, "com.google"),
                    scopesToString(userProperties.getArray("scopes")));

            userProperties.putString("accessToken", token);
        }

        private void handleException(RNGoogleSigninModule moduleInstance, Exception cause,
                                     WritableMap userProperties, @Nullable WritableMap settings) {
            boolean isRecoverable = cause instanceof UserRecoverableAuthException;
            if (isRecoverable) {
                boolean shouldRecover = settings != null
                        && settings.hasKey(SHOULD_RECOVER)
                        && settings.getBoolean(SHOULD_RECOVER);
                if (shouldRecover) {
                    attemptRecovery(moduleInstance, cause, userProperties);
                } else {
                    moduleInstance.promiseWrapper.reject(ERROR_USER_RECOVERABLE_AUTH, cause);
                }
            } else {
                moduleInstance.promiseWrapper.reject(MODULE_NAME, cause);
            }
        }

        private void attemptRecovery(RNGoogleSigninModule moduleInstance, Exception e, WritableMap userProperties) {
            Activity activity = moduleInstance.getCurrentActivity();
            if (activity == null) {
                moduleInstance.pendingAuthRecovery = null;
                moduleInstance.promiseWrapper.reject(MODULE_NAME,
                        "Cannot attempt recovery auth because app is not in foreground. "
                                + e.getLocalizedMessage());
            } else {
                moduleInstance.pendingAuthRecovery = new PendingAuthRecovery(userProperties);
                Intent recoveryIntent =
                        ((UserRecoverableAuthException) e).getIntent();
                activity.startActivityForResult(recoveryIntent, REQUEST_CODE_RECOVER_AUTH);
            }
        }
    }

    private static class TokenClearingTask extends AsyncTask<String, Void, Void> {

        private WeakReference<RNGoogleSigninModule> weakModuleRef;

        TokenClearingTask(RNGoogleSigninModule module) {
            this.weakModuleRef = new WeakReference<>(module);
        }

        @Override
        protected Void doInBackground(String... tokenToClear) {
            RNGoogleSigninModule moduleInstance = weakModuleRef.get();
            if (moduleInstance == null) {
                return null;
            }
            try {
                GoogleAuthUtil.clearToken(moduleInstance.getReactApplicationContext(), tokenToClear[0]);
                moduleInstance.getPromiseWrapper().resolve(null);
            } catch (Exception e) {
                moduleInstance.promiseWrapper.reject(MODULE_NAME, e);
            }
            return null;
        }
    }

    private void rejectWithNullClientError(Promise promise) {
        promise.reject(MODULE_NAME, "apiClient is null - call configure first");
    }

}
