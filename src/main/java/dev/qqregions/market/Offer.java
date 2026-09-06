package dev.qqregions.market;

import java.util.UUID;

/**
 * Предложение рынка: продажа или аренда региона.
 *
 * SALE createdBy="SELLER" — продавец предлагает купить конкретному игроку
 *     (seller; buyer=targer); принимает БАЙЕР.
 * SALE createdBy="BUYER"  — покупатель запрашивает покупку у владельца
 *     (seller=владелец региона; buyer=инициатор); принимает ПРОДАВЕЦ.
 * RENT createdBy="OWNER"  — владелец предлагает аренду (owner, tenant=targer);
 *     принимает АРЕНДАТОР.
 * RENT createdBy="TENANT" — арендатор запрашивает аренду (tenant, owner=владелец);
 *     принимает ВЛАДЕЛЕЦ.
 *
 * Активная аренда (ACTIVE) хранит until (когда срок кончается) и lastCharge
 * (последнее списание при charge=PERIOD).
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
    /** Кто создал: SELLER|BUYER (для SALE), OWNER|TENANT (для RENT). */
    public String createdBy;
    public double price;
    /** RENT: длительность срока/периода (миллисекунды). */
    public long periodMillis;
    public long created;
    public long until;
    public long lastCharge;
    public Status status = Status.PENDING;

    public Offer(UUID id, Kind kind) {
        this.id = id;
        this.kind = kind;
    }
}