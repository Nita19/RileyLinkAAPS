package info.nightscout.androidaps.plugins.pump.medtronic.comm.history.pump;

import java.util.Objects;

import org.joda.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.plugins.pump.common.utils.HexDump;
import info.nightscout.androidaps.plugins.pump.common.utils.StringUtil;
import info.nightscout.androidaps.plugins.pump.medtronic.comm.history.MedtronicHistoryEntry;

/**
 * Application: GGC - GNU Gluco Control
 * Plug-in: GGC PlugIn Base (base class for all plugins)
 * <p>
 * See AUTHORS for copyright information.
 * <p>
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free
 * Software Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 * <p>
 * Filename: PumpHistoryEntry Description: Pump History Entry.
 * <p>
 * Author: Andy {andy@atech-software.com}
 */

public class PumpHistoryEntry extends MedtronicHistoryEntry {

    private static Logger LOG = LoggerFactory.getLogger(PumpHistoryEntry.class);

    private PumpHistoryEntryType entryType;
    private Integer opCode; // this is set only when we have unknown entry...
    // private LocalDateTime timeOfEntry;
    private int offset;


    public PumpHistoryEntryType getEntryType() {
        return entryType;
    }


    public void setEntryType(PumpHistoryEntryType entryType) {
        this.entryType = entryType;

        this.sizes[0] = entryType.getHeadLength();
        this.sizes[1] = entryType.getDateLength();
        this.sizes[2] = entryType.getBodyLength();
    }


    @Override
    public int getOpCode() {
        if (opCode == null)
            return entryType.getOpCode();
        else
            return opCode;
    }


    public void setOpCode(Integer opCode) {
        this.opCode = opCode;
    }


    @Override
    public String getToStringStart() {
        return "PumpHistoryRecord [type=" + StringUtil.getStringInLength(entryType.name(), 20) + " ["
            + StringUtil.getStringInLength("" + getOpCode(), 3) + ", 0x"
            + HexDump.getCorrectHexValue((byte)getOpCode()) + "]";
    }


    // public PumpTimeStampedRecord getHistoryEntryDetails() {
    // return historyEntryDetails;
    // }
    //
    //
    // public void setHistoryEntryDetails(PumpTimeStampedRecord historyEntryDetails) {
    // this.historyEntryDetails = historyEntryDetails;
    // }

    public int getOffset() {
        return offset;
    }


    public void setOffset(int offset) {
        this.offset = offset;
    }


    @Override
    public String getEntryTypeName() {
        return this.entryType.name();
    }


    @Override
    public int getDateLength() {
        return this.entryType.getDateLength();
    }


    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;

        if (!(o instanceof PumpHistoryEntry))
            return false;

        PumpHistoryEntry that = (PumpHistoryEntry)o;

        return entryType == that.entryType && //
            Objects.equals(this.dateTime, that.dateTime); // && //
        // Objects.equals(this.decodedData, that.decodedData);
    }


    @Override
    public int hashCode() {
        return Objects.hash(entryType, opCode, offset);
    }


    public boolean isAfter(LocalDateTime dateTimeIn) {
        // LOG.debug("Entry: " + this.dateTime);
        // LOG.debug("Datetime: " + dateTimeIn);
        // LOG.debug("Item after: " + this.dateTime.isAfter(dateTimeIn));
        return this.dateTime.isAfter(dateTimeIn);
    }

    public static class Comparator implements java.util.Comparator<PumpHistoryEntry> {

        @Override
        public int compare(PumpHistoryEntry o1, PumpHistoryEntry o2) {
            return o2.dateTime.compareTo(o1.dateTime);
        }
    }
}
