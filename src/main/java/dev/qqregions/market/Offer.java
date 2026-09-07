package dev.qqregions.market;

import java.util.UUID;

/**
 * Предложение рынка: продажа или аренда региона.
 *
 * SALE createdBy="SELLER" — продавец выставляет регион на продажу:
 *     buyer=null — ПУБЛИЧНОЕ объявление (любой купит мгновенно);
 *     buyer=ник — приватное предложение (принимает покупатель).
 * RENT createdBy="OWNER"  — владелец сдаёт регион в аренду:
 *     tenant=null — ПУБЛИЧНОЕ объявление (любой арендует мгновенно);
 *     tenant=ник — приватное предложение (принимает арендатор).
 *
 * Активная аренда (ACTIVE) хранит until (когда срок кончается) и lastCharge
 * (последнее списание при charge=PERIOD).
 *
 * Публичное объявление живёт в маркете, пока не пройдёт listUntil (срок
 * объявления, listDurationMillis), и принимается ЛЮБЫМ игроком, кроме
 * продавца/владельца. По окончании аренды объявление снова выставляется,
 * если autoRent.
 */
public final class Offer {

    public enum Kind { SALE, RENT }

    public enum Status { PENDING, ACTIVE, DONE, DECLINED, CANCELLED }

    public final UUID id;
    public final Kind kind;
    public String world;
    public String region;
    public UUID seller;
    public UUID buyer;
    public UUID owner;
    public UUID tenant;
    /** Кто создал: SELLER (для SALE), OWNER (для RENT). */
    public String createdBy;
    public double price;
    /** RENT: длительность срока аренды (миллисекунды). */
    public long periodMillis;
    public long created;
    /** RENT: когда заканчивается текущая аренда. */
    public long until;
    public long lastCharge;
    /** Момент, когда публичное объявление само уйдёт с рынка (0 = не действует). */
    public long listUntil;
    /** Желаемый срок объявления (млс): длительность автовозврата/перевыставления. */
    public long listDurationMillis;
    /** Момент, когда ПРИВАТНОЕ предложение уйдёт само, если контрагент не принял (0 = без срока). */
    public long pendingUntil;
    /** Автовозврат с автопродлением: после конца аренды пере-выставить объявление. */
    public boolean autoRent = true;
    public Status status = Status.PENDING;

    public Offer(UUID id, Kind kind) {
        this.id = id;
        this.kind = kind;
    }

    /** Публичное объявление (продаётся/сдаётся любому мгновенно). */
    public boolean isPublicListing() {
        if (status == Status.ACTIVE) {
            if (kind == Kind.SALE) {
                return buyer == null;
            }
            return tenant == null;
        }
        return false;
    }

    /** Идёт прямо сейчас аренда (у объявления есть арендатор). */
    public boolean isActiveRental() {
        return kind == Kind.RENT && status == Status.ACTIVE && tenant != null;
    }
}