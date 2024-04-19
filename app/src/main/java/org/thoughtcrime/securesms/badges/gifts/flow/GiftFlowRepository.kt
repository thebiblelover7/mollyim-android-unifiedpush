package org.thoughtcrime.securesms.badges.gifts.flow

import android.content.Context
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.logging.Log
import org.signal.core.util.money.FiatMoney
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.badges.Badges
import org.thoughtcrime.securesms.badges.models.Badge
import org.thoughtcrime.securesms.components.settings.app.subscription.DonationSerializationHelper.toFiatValue
import org.thoughtcrime.securesms.components.settings.app.subscription.InAppPaymentsRepository
import org.thoughtcrime.securesms.components.settings.app.subscription.getGiftBadgeAmounts
import org.thoughtcrime.securesms.components.settings.app.subscription.getGiftBadges
import org.thoughtcrime.securesms.database.InAppPaymentTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.databaseprotos.InAppPaymentData
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.whispersystems.signalservice.internal.push.SubscriptionsConfiguration
import java.util.Currency
import java.util.Locale

/**
 * Repository for grabbing gift badges and supported currency information.
 */
class GiftFlowRepository {

  companion object {
    private val TAG = Log.tag(GiftFlowRepository::class.java)
  }

  fun insertInAppPayment(context: Context, giftSnapshot: GiftFlowState): Single<InAppPaymentTable.InAppPayment> {
    return Single.fromCallable {
      SignalDatabase.inAppPayments.insert(
        type = InAppPaymentTable.Type.ONE_TIME_GIFT,
        state = InAppPaymentTable.State.CREATED,
        subscriberId = null,
        endOfPeriod = null,
        inAppPaymentData = InAppPaymentData(
          badge = Badges.toDatabaseBadge(giftSnapshot.giftBadge!!),
          label = context.getString(R.string.preferences__one_time),
          amount = giftSnapshot.giftPrices[giftSnapshot.currency]!!.toFiatValue(),
          level = giftSnapshot.giftLevel!!,
          recipientId = giftSnapshot.recipient!!.id.serialize(),
          additionalMessage = giftSnapshot.additionalMessage?.toString()
        )
      )
    }.flatMap { InAppPaymentsRepository.requireInAppPayment(it) }.subscribeOn(Schedulers.io())
  }

  fun getGiftBadge(): Single<Pair<Int, Badge>> {
    return Single
      .fromCallable {
        ApplicationDependencies.getDonationsService()
          .getDonationsConfiguration(Locale.getDefault())
      }
      .flatMap { it.flattenResult() }
      .map { SubscriptionsConfiguration.GIFT_LEVEL to it.getGiftBadges().first() }
      .subscribeOn(Schedulers.io())
  }

  fun getGiftPricing(): Single<Map<Currency, FiatMoney>> {
    return Single
      .fromCallable {
        ApplicationDependencies.getDonationsService()
          .getDonationsConfiguration(Locale.getDefault())
      }
      .subscribeOn(Schedulers.io())
      .flatMap { it.flattenResult() }
      .map { it.getGiftBadgeAmounts() }
  }
}
