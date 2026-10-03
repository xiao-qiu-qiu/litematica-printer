package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;

public enum PrintOrderMode implements ConfigOptionListEntry<PrintOrderMode> {
    ROUTE("printOrder.route"),
    NEAREST("printOrder.nearest"),
    COORDINATES("printOrder.coordinates");

    private final I18n i18n;

    PrintOrderMode(String key) { i18n = I18n.of(key); }

    @Override
    public I18n getI18n() { return i18n; }
}
