package com.mapswithme.maps.purchase;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.widget.CardView;
import android.text.Html;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.billingclient.api.SkuDetails;
import com.mapswithme.maps.R;
import com.mapswithme.maps.base.BaseMwmFragment;
import com.mapswithme.maps.bookmarks.data.BookmarkManager;
import com.mapswithme.maps.dialog.AlertDialogCallback;
import com.mapswithme.util.UiUtils;
import com.mapswithme.util.Utils;
import com.mapswithme.util.log.Logger;
import com.mapswithme.util.log.LoggerFactory;

import java.util.Collections;
import java.util.List;

public class BookmarkSubscriptionFragment extends BaseMwmFragment
    implements AlertDialogCallback, PurchaseStateActivator<BookmarkSubscriptionPaymentState>
{
  private static final Logger LOGGER = LoggerFactory.INSTANCE.getLogger(LoggerFactory.Type.BILLING);
  private static final String TAG = BookmarkSubscriptionFragment.class.getSimpleName();
  private final static String EXTRA_CURRENT_STATE = "extra_current_state";
  private final static String EXTRA_PRODUCT_DETAILS = "extra_product_details";
  private static final int DEF_ELEVATION = 0;

  @SuppressWarnings("NullableProblems")
  @NonNull
  private PurchaseController<PurchaseCallback> mPurchaseController;
  @NonNull
  private final BookmarkSubscriptionCallback mPurchaseCallback = new BookmarkSubscriptionCallback();
  @NonNull
  private final PingCallback mPingCallback = new PingCallback();
  @NonNull
  private BookmarkSubscriptionPaymentState mState = BookmarkSubscriptionPaymentState.NONE;
  @Nullable
  private ProductDetails[] mProductDetails;
  private boolean mValidationResult;
  private boolean mPingingResult;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState)
  {
    mPurchaseController = PurchaseFactory.createBookmarksSubscriptionPurchaseController(requireContext());
    if (savedInstanceState != null)
      mPurchaseController.onRestore(savedInstanceState);
    mPurchaseController.initialize(requireActivity());
    mPingCallback.attach(this);
    BookmarkManager.INSTANCE.addCatalogPingListener(mPingCallback);
    View root = inflater.inflate(R.layout.bookmark_subscription_fragment, container, false);
    CardView annualPriceCard = root.findViewById(R.id.annual_price_card);
    CardView monthlyPriceCard = root.findViewById(R.id.monthly_price_card);
    View annualCardEdge = root.findViewById(R.id.annual_price_card_edge);
    View monthlyCardEdge = root.findViewById(R.id.monthly_price_card_edge);
    AnnualCardClickListener annualCardListener = new AnnualCardClickListener(monthlyPriceCard,
                                                                             annualPriceCard,
                                                                             annualCardEdge,
                                                                             monthlyCardEdge);
    annualPriceCard.setOnClickListener(annualCardListener);
    MonthlyCardClickListener monthlyCardListener = new MonthlyCardClickListener(monthlyPriceCard,
                                                                                annualPriceCard,
                                                                                annualCardEdge,
                                                                                monthlyCardEdge);
    monthlyPriceCard.setOnClickListener(monthlyCardListener);
    annualPriceCard.setCardElevation(getResources().getDimension(R.dimen.margin_base_plus_quarter));

    TextView restorePurchasesLink = root.findViewById(R.id.restore_purchase_btn);
    final Spanned html = makeRestorePurchaseHtml(requireContext());
    restorePurchasesLink.setText(html);
    restorePurchasesLink.setMovementMethod(LinkMovementMethod.getInstance());
    restorePurchasesLink.setOnClickListener(v -> openSubscriptionManagementSettings());

    View continueBtn = root.findViewById(R.id.continue_btn);
    continueBtn.setOnClickListener(v -> onContinueButtonClicked());
    return root;
  }

  @Override
  public void onDestroyView()
  {
    super.onDestroyView();
    mPingCallback.detach();
    BookmarkManager.INSTANCE.removeCatalogPingListener(mPingCallback);
  }

  private void openSubscriptionManagementSettings()
  {
    Utils.openUrl(requireContext(), "https://play.google.com/store/account/subscriptions");
  }

  private void onContinueButtonClicked()
  {
    BookmarkManager.INSTANCE.pingBookmarkCatalog();
    activateState(BookmarkSubscriptionPaymentState.PINGING);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
  {
    super.onViewCreated(view, savedInstanceState);
    if (savedInstanceState != null)
    {
      BookmarkSubscriptionPaymentState savedState
          = BookmarkSubscriptionPaymentState.values()[savedInstanceState.getInt(EXTRA_CURRENT_STATE)];
      ProductDetails[] productDetails
          = (ProductDetails[]) savedInstanceState.getParcelableArray(EXTRA_PRODUCT_DETAILS);
      if (productDetails != null)
        mProductDetails = productDetails;

      activateState(savedState);
      return;
    }

    activateState(BookmarkSubscriptionPaymentState.PRODUCT_DETAILS_LOADING);
    mPurchaseController.queryProductDetails();
  }

  void queryProductDetails()
  {
    mPurchaseController.queryProductDetails();
  }

  @Override
  public void onStart()
  {
    super.onStart();
    mPurchaseController.addCallback(mPurchaseCallback);
    mPurchaseCallback.attach(this);
  }

  @Override
  public void onStop()
  {
    super.onStop();
    mPurchaseController.removeCallback();
    mPurchaseCallback.detach();
  }

  private static Spanned makeRestorePurchaseHtml(@NonNull Context context)
  {
    final String restorePurchaseLink = "";
    return Html.fromHtml(context.getString(R.string.restore_purchase_link,
                                           restorePurchaseLink));
  }

  @Override
  public void activateState(@NonNull BookmarkSubscriptionPaymentState state)
  {
    if (state == mState)
      return;

    LOGGER.i(TAG, "Activate state: " + state);
    mState = state;
    mState.activate(this);
  }

  private void handleProductDetails(@NonNull List<SkuDetails> details)
  {
    mProductDetails = new ProductDetails[PurchaseUtils.Period.values().length];
    for (SkuDetails sku: details)
    {
      PurchaseUtils.Period period = PurchaseUtils.Period.valueOf(sku.getSubscriptionPeriod());
      mProductDetails[period.ordinal()] = PurchaseUtils.toProductDetails(sku);
    }
  }

  void updatePaymentButtons()
  {
    updateYearlyButton();
    updateMonthlyButton();
  }

  private void updateYearlyButton()
  {
    ProductDetails details = getProductDetailsForPeriod(PurchaseUtils.Period.P1Y);
    String price = Utils.formatCurrencyString(details.getPrice(), details.getCurrencyCode());
    TextView priceView = getViewOrThrow().findViewById(R.id.annual_price);
    priceView.setText(price);
    TextView savingView = getViewOrThrow().findViewById(R.id.sale);
    String saving = Utils.formatCurrencyString(calculateYearlySaving(), details.getCurrencyCode());
    savingView.setText(getString(R.string.annual_save_component, saving));
  }

  private void updateMonthlyButton()
  {
    ProductDetails details = getProductDetailsForPeriod(PurchaseUtils.Period.P1M);
    String price = Utils.formatCurrencyString(details.getPrice(), details.getCurrencyCode());
    TextView priceView = getViewOrThrow().findViewById(R.id.monthly_price);
    priceView.setText(price);
  }

  private float calculateYearlySaving()
  {
    float pricePerMonth = getProductDetailsForPeriod(PurchaseUtils.Period.P1M).getPrice();
    float pricePerYear = getProductDetailsForPeriod(PurchaseUtils.Period.P1Y).getPrice();
    return pricePerMonth * PurchaseUtils.MONTHS_IN_YEAR - pricePerYear;
  }

  @NonNull
  private ProductDetails getProductDetailsForPeriod(@NonNull PurchaseUtils.Period period)
  {
    if (mProductDetails == null)
      throw new AssertionError("Product details must be exist at this moment!");
    return mProductDetails[period.ordinal()];
  }

  @Override
  public void onAlertDialogPositiveClick(int requestCode, int which)
  {
    // TODO: coming soon.
  }

  @Override
  public void onAlertDialogNegativeClick(int requestCode, int which)
  {
    // TODO: coming soon.
  }

  @Override
  public void onAlertDialogCancel(int requestCode)
  {
    // TODO: coming soon.
  }

  private void handleActivationResult(boolean result)
  {
    mValidationResult = result;
  }

  private void handlePingingResult(boolean result)
  {
    mPingingResult = result;
  }

  void finishValidation()
  {
    if (mValidationResult)
      requireActivity().setResult(Activity.RESULT_OK);

    requireActivity().finish();
  }

  public void finishPinging()
  {
    if (mPingingResult)
    {
      launchPurchaseFlow();
      return;
    }

    PurchaseUtils.showPingFailureDialog(this);
  }

  private void launchPurchaseFlow()
  {
    CardView annualCard = getViewOrThrow().findViewById(R.id.annual_price_card);
    PurchaseUtils.Period period = annualCard.getCardElevation() > 0 ? PurchaseUtils.Period.P1Y
                                                                    : PurchaseUtils.Period.P1M;
    ProductDetails details = getProductDetailsForPeriod(period);
    mPurchaseController.launchPurchaseFlow(details.getProductId());
  }

  private class AnnualCardClickListener implements View.OnClickListener
  {
    @NonNull
    private final CardView mMonthlyPriceCard;

    @NonNull
    private final CardView mAnnualPriceCard;

    @NonNull
    private final View mAnnualCardFrame;

    @NonNull
    private final View mMonthlyCardFrame;

    AnnualCardClickListener(@NonNull CardView monthlyPriceCard,
                            @NonNull CardView annualPriceCard,
                            @NonNull View annualCardFrame,
                            @NonNull View monthlyCardFrame)
    {
      mMonthlyPriceCard = monthlyPriceCard;
      mAnnualPriceCard = annualPriceCard;
      mAnnualCardFrame = annualCardFrame;
      mMonthlyCardFrame = monthlyCardFrame;
    }

    @Override
    public void onClick(View v)
    {
      mMonthlyPriceCard.setCardElevation(DEF_ELEVATION);
      mAnnualPriceCard.setCardElevation(getResources().getDimension(R.dimen.margin_base_plus_quarter));
      UiUtils.show(mAnnualCardFrame, mMonthlyCardFrame);
    }
  }

  private class MonthlyCardClickListener implements View.OnClickListener
  {
    @NonNull
    private final CardView mMonthlyPriceCard;

    @NonNull
    private final CardView mAnnualPriceCard;

    @NonNull
    private final View mAnnualCardFrame;

    @NonNull
    private final View mMonthlyCardFrame;

    MonthlyCardClickListener(@NonNull CardView monthlyPriceCard,
                             @NonNull CardView annualPriceCard,
                             @NonNull View annualCardFrame,
                             @NonNull View monthlyCardFrame)
    {
      mMonthlyPriceCard = monthlyPriceCard;
      mAnnualPriceCard = annualPriceCard;
      mAnnualCardFrame = annualCardFrame;
      mMonthlyCardFrame = monthlyCardFrame;
    }

    @Override
    public void onClick(View v)
    {
      mMonthlyPriceCard.setCardElevation(getResources().getDimension(R.dimen.margin_base_plus_quarter));
      mAnnualPriceCard.setCardElevation(DEF_ELEVATION);
      UiUtils.hide(mAnnualCardFrame, mMonthlyCardFrame);
    }
  }

  private static class BookmarkSubscriptionCallback
      extends StatefulPurchaseCallback<BookmarkSubscriptionPaymentState, BookmarkSubscriptionFragment>
      implements PurchaseCallback
  {
    @Nullable
    private List<SkuDetails> mPendingDetails;
    private Boolean mPendingValidationResult;

    @Override
    public void onProductDetailsLoaded(@NonNull List<SkuDetails> details)
    {
      if (PurchaseUtils.hasIncorrectSkuDetails(details))
      {
        activateStateSafely(BookmarkSubscriptionPaymentState.PRODUCT_DETAILS_FAILURE);
        return;
      }

      if (getUiObject() == null)
        mPendingDetails = Collections.unmodifiableList(details);
      else
        getUiObject().handleProductDetails(details);
      activateStateSafely(BookmarkSubscriptionPaymentState.PRICE_SELECTION);
    }

    @Override
    public void onPaymentFailure(int error)
    {
      activateStateSafely(BookmarkSubscriptionPaymentState.PAYMENT_FAILURE);
    }

    @Override
    public void onProductDetailsFailure()
    {
      activateStateSafely(BookmarkSubscriptionPaymentState.PRODUCT_DETAILS_FAILURE);
    }

    @Override
    public void onStoreConnectionFailed()
    {
      activateStateSafely(BookmarkSubscriptionPaymentState.PRODUCT_DETAILS_FAILURE);
    }

    @Override
    public void onValidationStarted()
    {
      activateStateSafely(BookmarkSubscriptionPaymentState.VALIDATION);
    }

    @Override
    public void onValidationFinish(boolean success)
    {
      if (getUiObject() == null)
        mPendingValidationResult = success;
      else
        getUiObject().handleActivationResult(success);

      activateStateSafely(BookmarkSubscriptionPaymentState.VALIDATION_FINISH);
    }

    @Override
    void onAttach(@NonNull BookmarkSubscriptionFragment bookmarkSubscriptionFragment)
    {
      if (mPendingDetails != null)
      {
        bookmarkSubscriptionFragment.handleProductDetails(mPendingDetails);
        mPendingDetails = null;
      }

      if (mPendingValidationResult != null)
      {
        bookmarkSubscriptionFragment.handleActivationResult(mPendingValidationResult);
        mPendingValidationResult = null;
      }
    }
  }

  private static class PingCallback
      extends StatefulPurchaseCallback<BookmarkSubscriptionPaymentState,
      BookmarkSubscriptionFragment> implements BookmarkManager.BookmarksCatalogPingListener

  {
    private Boolean mPendingPingingResult;

    @Override
    public void onPingFinished(boolean isServiceAvailable)
    {
      if (getUiObject() == null)
        mPendingPingingResult = isServiceAvailable;
      else
        getUiObject().handlePingingResult(isServiceAvailable);

      activateStateSafely(BookmarkSubscriptionPaymentState.PINGING_FINISH);
    }

    @Override
    void onAttach(@NonNull BookmarkSubscriptionFragment fragment)
    {
      if (mPendingPingingResult != null)
      {
        fragment.handlePingingResult(mPendingPingingResult);
        mPendingPingingResult = null;
      }
    }
  }
}
