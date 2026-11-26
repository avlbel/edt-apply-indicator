package com.avlbel.edt.applyindicator;

import java.util.List;

import com.e1c.g5.dt.applications.ApplicationUpdateState;

/**
 * Сводка состояний применения по приложениям текущего проекта.
 * <p>
 * Источник каждого значения — {@link ApplicationUpdateState} из EDT 2026.2
 * (бандл {@code com.e1c.g5.dt.applications} 7.0.1). Набор констант тот же,
 * что читает декоратор представления «Приложения» ({@code ApplicationsDecorator}):
 * зелёная галочка для {@code UPDATED}, жёлтая звёздочка для неприменённой базы.
 * {@code UNKNOWN} галочку и звёздочку не рисует, поэтому в сводке он не
 * считается ни применённым, ни неприменённым.
 */
final class ApplyStatus {

    enum Level {
        APPLIED,
        NOT_APPLIED,
        NEUTRAL
    }

    private final Level level;
    private final int total;
    private final int notApplied;
    private final int updating;
    private final int unknown;

    private ApplyStatus(Level level, int total, int notApplied, int updating, int unknown) {
        this.level = level;
        this.total = total;
        this.notApplied = notApplied;
        this.updating = updating;
        this.unknown = unknown;
    }

    static ApplyStatus of(List<ApplicationUpdateState> states) {
        int applied = 0;
        int notApplied = 0;
        int updating = 0;
        int unknown = 0;
        for (ApplicationUpdateState state : states) {
            if (state == null || state == ApplicationUpdateState.UNKNOWN) {
                unknown++;
            } else if (state == ApplicationUpdateState.UPDATED) {
                applied++;
            } else if (state == ApplicationUpdateState.BEING_UPDATED) {
                updating++;
            } else {
                // INCREMENTAL_UPDATE_REQUIRED и FULL_UPDATE_REQUIRED:
                // конфигурация проекта в базу ещё не загружена.
                notApplied++;
            }
        }
        int total = states.size();
        Level level;
        if (total == 0) {
            level = Level.NEUTRAL;
        } else if (notApplied > 0 || updating > 0) {
            level = Level.NOT_APPLIED;
        } else if (applied == total) {
            level = Level.APPLIED;
        } else {
            level = Level.NEUTRAL;
        }
        return new ApplyStatus(level, total, notApplied, updating, unknown);
    }

    Level level() {
        return level;
    }

    int total() {
        return total;
    }

    int notApplied() {
        return notApplied;
    }

    int updating() {
        return updating;
    }

    int unknown() {
        return unknown;
    }
}
