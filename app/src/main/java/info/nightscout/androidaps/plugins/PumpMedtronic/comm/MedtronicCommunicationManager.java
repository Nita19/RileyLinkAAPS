package info.nightscout.androidaps.plugins.PumpMedtronic.comm;

import java.util.ArrayList;
import java.util.Map;

import org.joda.time.Instant;
import org.joda.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.Context;
import android.os.SystemClock;

import com.gxwtech.roundtrip2.RoundtripService.medtronic.PumpData.ISFTable;

import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.RileyLinkCommunicationManager;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.RFSpy;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.data.RLMessage;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.defs.RLMessageType;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.defs.RileyLinkTargetFrequency;
import info.nightscout.androidaps.plugins.PumpCommon.utils.ByteUtil;
import info.nightscout.androidaps.plugins.PumpCommon.utils.HexDump;
import info.nightscout.androidaps.plugins.PumpMedtronic.comm.data.Page;
import info.nightscout.androidaps.plugins.PumpMedtronic.comm.data.history_old.Record;
import info.nightscout.androidaps.plugins.PumpMedtronic.comm.history.RawHistoryPage;
import info.nightscout.androidaps.plugins.PumpMedtronic.comm.message.ButtonPressCarelinkMessageBody;
import info.nightscout.androidaps.plugins.PumpMedtronic.comm.message.CarelinkLongMessageBody;
import info.nightscout.androidaps.plugins.PumpMedtronic.comm.message.CarelinkShortMessageBody;
import info.nightscout.androidaps.plugins.PumpMedtronic.comm.message.GetHistoryPageCarelinkMessageBody;
import info.nightscout.androidaps.plugins.PumpMedtronic.comm.message.MessageBody;
import info.nightscout.androidaps.plugins.PumpMedtronic.comm.message.PacketType;
import info.nightscout.androidaps.plugins.PumpMedtronic.comm.message.PumpAckMessageBody;
import info.nightscout.androidaps.plugins.PumpMedtronic.comm.message.PumpMessage;
import info.nightscout.androidaps.plugins.PumpMedtronic.data.dto.BasalProfile;
import info.nightscout.androidaps.plugins.PumpMedtronic.data.dto.BatteryStatusDTO;
import info.nightscout.androidaps.plugins.PumpMedtronic.data.dto.PumpSettingDTO;
import info.nightscout.androidaps.plugins.PumpMedtronic.data.dto.TempBasalPair;
import info.nightscout.androidaps.plugins.PumpMedtronic.defs.MedtronicCommandType;
import info.nightscout.androidaps.plugins.PumpMedtronic.defs.MedtronicDeviceType;
import info.nightscout.androidaps.plugins.PumpMedtronic.defs.PumpDeviceState;
import info.nightscout.androidaps.plugins.PumpMedtronic.service.RileyLinkMedtronicService;
import info.nightscout.androidaps.plugins.PumpMedtronic.util.MedtronicUtil;

/**
 * Original file created by geoff on 5/30/16.
 * <p>
 * Split into 2 implementations, so that we can split it by target device. - Andy
 */
public class MedtronicCommunicationManager extends RileyLinkCommunicationManager {

    private static final Logger LOG = LoggerFactory.getLogger(MedtronicCommunicationManager.class);
    private static final int MAX_COMMAND_RETRIES = 2;
    private static final int DEFAULT_TIMEOUT = 2000;

    static MedtronicCommunicationManager medtronicCommunicationManager;
    String errorMessage;
    private MedtronicConverter medtronicConverter;
    private boolean debugSetCommands = true;


    public MedtronicCommunicationManager(Context context, RFSpy rfspy, RileyLinkTargetFrequency targetFrequency) {
        super(context, rfspy, targetFrequency);
        medtronicCommunicationManager = this;
        this.medtronicConverter = new MedtronicConverter();
    }


    public static MedtronicCommunicationManager getInstance() {
        return medtronicCommunicationManager;
    }


    @Override
    protected void configurePumpSpecificSettings() {
        pumpStatus = MedtronicUtil.getPumpStatus();
    }


    @Override
    public <E extends RLMessage> E createResponseMessage(byte[] payload, Class<E> clazz) {
        PumpMessage pumpMessage = new PumpMessage(payload);
        return (E)pumpMessage;
    }


    // FIXME must not call getPumpModel !!!!!!!!!!!!!
    @Override
    public boolean tryToConnectToDevice() {

        wakeUp(true);

        MedtronicDeviceType pumpModel = getPumpModel();

        // Andy (4.6.2018): we do retry if no data returned. We might need to do that everywhere, but that might require
        // little bit of rewrite of RF Code.
        if (pumpModel == MedtronicDeviceType.Unknown_Device) {

            SystemClock.sleep(1000);

            pumpModel = getPumpModel();
        }

        boolean connected = (pumpModel != MedtronicDeviceType.Unknown_Device);

        if (connected) {
            // checkFirstConnectionTime();
            setLastConnectionTime();
        }

        return (pumpModel != MedtronicDeviceType.Unknown_Device);
    }


    private void setLastConnectionTime() {

        // FIXME rename
        this.pumpStatus.setLastCommunicationToNow();

        // FIXME set to SP

    }


    // FIXME remove debugs - Andy
    private PumpMessage runCommandWithArgs(PumpMessage msg) {

        if (debugSetCommands)
            LOG.debug("Run command with Args: ");
        PumpMessage rval;
        PumpMessage shortMessage = makePumpMessage(msg.commandType, new CarelinkShortMessageBody(new byte[] { 0 }));
        // look for ack from short message
        PumpMessage shortResponse = sendAndListen(shortMessage);
        if (shortResponse.commandType == MedtronicCommandType.CommandACK) {
            if (debugSetCommands)
                LOG.debug("Run command with Args: Got ACK response");
            rval = sendAndListen(msg);
            if (debugSetCommands)
                LOG.debug("2nd Response: {}", rval);
            return rval;
        } else {
            LOG.error("runCommandWithArgs: Pump did not ack Attention packet");
            return new PumpMessage("No ACK after Attention packet.");
        }
    }


    private PumpMessage runCommandWithArgsLong(MedtronicCommandType commandType, byte[] content) {

        LOG.debug("Run command with Args (Long): {}", commandType.name());

        PumpMessage rval = null;
        PumpMessage shortMessage = makePumpMessage(commandType, new CarelinkShortMessageBody(new byte[] { 0 }));
        // look for ack from short message
        PumpMessage shortResponse = sendAndListen(shortMessage);

        if (shortResponse.commandType != MedtronicCommandType.CommandACK) {
            LOG.error("runCommandWithArgs: Pump did not ack Attention packet");

            return new PumpMessage("No ACK after start message.");
        }

        int start = 0;
        int frameNr = 1;
        int len = 0;

        do {

            if (start == 0)
                LOG.debug("Run command with Args(Long): Got ACK response for Attention packet");
            else
                LOG.debug("Run command with Args(Long): Got ACK response for frame #{}", (frameNr - 1));

            if (start + 64 > content.length) {
                len = content.length - start;

                if (len == 0)
                    break;
            } else {
                len = 64;
            }

            byte frame[] = new byte[65];

            frame[0] = (byte)frameNr;

            System.arraycopy(content, start, frame, 1, len);

            PumpMessage msg = makePumpMessage(commandType, new CarelinkLongMessageBody(frame));

            rval = sendAndListen(msg);

            if (rval.commandType != MedtronicCommandType.CommandACK) {
                LOG.error("runCommandWithArgs(Long): Pump did not ACK frame #{}", frameNr);

                return new PumpMessage("No ACK after frame #" + frameNr);
            }

            if (len != 64) {
                LOG.debug("Run command with Args(Long): Got ACK response for frame #{}", (frameNr));
                break;
            }

            start += 64;
            frameNr++;

        } while (true);

        return rval;

        // return new PumpMessage("No ACK");
    }


    public Page getPumpHistoryPage(int pageNumber) {
        RawHistoryPage rval = new RawHistoryPage();
        wakeUp(receiverDeviceAwakeForMinutes, false);
        PumpMessage getHistoryMsg = makePumpMessage(MedtronicCommandType.GetHistoryData,
            new GetHistoryPageCarelinkMessageBody(pageNumber));
        // LOG.info("getPumpHistoryPage("+pageNumber+"): "+ByteUtil.shortHexString(getHistoryMsg.getTxData()));
        // Ask the pump to transfer history (we get first frame?)
        PumpMessage firstResponse = runCommandWithArgs(getHistoryMsg);
        // LOG.info("getPumpHistoryPage("+pageNumber+"): " + ByteUtil.shortHexString(firstResponse.getContents()));

        PumpMessage ackMsg = makePumpMessage(MedtronicCommandType.CommandACK, new PumpAckMessageBody());
        GetHistoryPageCarelinkMessageBody currentResponse = new GetHistoryPageCarelinkMessageBody(firstResponse
            .getMessageBody().getTxData());
        int expectedFrameNum = 1;
        boolean done = false;
        // while (expectedFrameNum == currentResponse.getFrameNumber()) {
        int failures = 0;
        while (!done) {
            // examine current response for problems.
            byte[] frameData = currentResponse.getFrameData();
            if ((frameData != null) && (frameData.length > 0) && currentResponse.getFrameNumber() == expectedFrameNum) {
                // success! got a frame.
                if (frameData.length != 64) {
                    LOG.warn("Expected frame of length 64, got frame of length " + frameData.length);
                    // but append it anyway?
                }
                // handle successful frame data
                rval.appendData(currentResponse.getFrameData());
                RileyLinkMedtronicService.getInstance().announceProgress(
                    ((100 / 16) * currentResponse.getFrameNumber() + 1));
                LOG.info("getPumpHistoryPage: Got frame " + currentResponse.getFrameNumber());
                // Do we need to ask for the next frame?
                if (expectedFrameNum < 16) { // This number may not be correct for pumps other than 522/722
                    expectedFrameNum++;
                } else {
                    done = true; // successful completion
                }
            } else {
                if (frameData == null) {
                    LOG.error("null frame data, retrying");
                } else if (currentResponse.getFrameNumber() != expectedFrameNum) {
                    LOG.warn("Expected frame number {}, received {} (retrying)", expectedFrameNum,
                        currentResponse.getFrameNumber());
                } else if (frameData.length == 0) {
                    LOG.warn("Frame has zero length, retrying");
                }
                failures++;
                if (failures == 6) {
                    LOG.error("6 failures in attempting to download frame {} of page {}, giving up.", expectedFrameNum,
                        pageNumber);
                    done = true; // failure completion.
                }
            }
            if (!done) {
                // ask for next frame
                PumpMessage nextMsg = sendAndListen(ackMsg);
                currentResponse = new GetHistoryPageCarelinkMessageBody(nextMsg.getMessageBody().getTxData());
            }
        }
        if (rval.getLength() != 1024) {
            LOG.warn("getPumpHistoryPage: short page.  Expected length of 1024, found length of " + rval.getLength());
        }
        if (!rval.isChecksumOK()) {
            LOG.error("getPumpHistoryPage: checksum is wrong");
        }

        rval.dumpToDebug();

        Page page = new Page();
        // page.parseFrom(rval.getData(),PumpModel.MM522);
        // FIXME
        page.parseFrom(rval.getData(), MedtronicDeviceType.Medtronic_522);

        return page;
    }


    public ArrayList<Page> getAllHistoryPages() {
        ArrayList<Page> pages = new ArrayList<>();

        for (int pageNum = 0; pageNum < 16; pageNum++) {
            pages.add(getPumpHistoryPage(pageNum));
        }

        return pages;
    }


    public ArrayList<Page> getHistoryEventsSinceDate(Instant when) {
        ArrayList<Page> pages = new ArrayList<>();
        for (int pageNum = 0; pageNum < 16; pageNum++) {
            pages.add(getPumpHistoryPage(pageNum));
            for (Page page : pages) {
                for (Record r : page.mRecordList) {
                    LocalDateTime timestamp = r.getTimestamp().getLocalDateTime();
                    LOG.info("Found record: (" + r.getClass().getSimpleName() + ") " + timestamp.toString());
                }
            }
        }
        return pages;
    }


    public String getErrorResponse() {
        return this.errorMessage;
    }


    // See ButtonPressCarelinkMessageBody
    public void pressButton(int which) {
        wakeUp(receiverDeviceAwakeForMinutes, false);
        PumpMessage pressButtonMessage = makePumpMessage(MedtronicCommandType.PushButton,
            new ButtonPressCarelinkMessageBody(which));
        PumpMessage resp = sendAndListen(pressButtonMessage);
        if (resp.commandType != MedtronicCommandType.CommandACK) {
            LOG.error("Pump did not ack button press.");
        }
    }


    @Override
    public byte[] createPumpMessageContent(RLMessageType type) {
        switch (type) {
            case PowerOn:
                return MedtronicUtil.buildCommandPayload(MedtronicCommandType.RFPowerOn, //
                    new byte[] { 2, 1, (byte)receiverDeviceAwakeForMinutes }); // maybe this is better FIXME

            case ReadSimpleData:
                return MedtronicUtil.buildCommandPayload(MedtronicCommandType.PumpModel, null);
        }
        return new byte[0];
    }


    protected PumpMessage makePumpMessage(MedtronicCommandType messageType, byte[] body) {
        return makePumpMessage(messageType, body == null ? new CarelinkShortMessageBody()
            : new CarelinkShortMessageBody(body));
    }


    protected PumpMessage makePumpMessage(MedtronicCommandType messageType) {
        return makePumpMessage(messageType, (byte[])null);
    }


    protected PumpMessage makePumpMessage(MedtronicCommandType messageType, MessageBody messageBody) {
        PumpMessage msg = new PumpMessage();
        msg.init(PacketType.Carelink, rileyLinkServiceData.pumpIDBytes, messageType, messageBody);
        return msg;
    }


    private PumpMessage sendAndGetResponse(MedtronicCommandType commandType) {

        return sendAndGetResponse(commandType, null, DEFAULT_TIMEOUT);
    }


    private PumpMessage sendAndGetResponse(MedtronicCommandType commandType, int timeoutMs) {

        return sendAndGetResponse(commandType, null, timeoutMs);
    }


    private PumpMessage sendAndGetResponse(MedtronicCommandType commandType, byte[] bodyData) {

        return sendAndGetResponse(commandType, bodyData, DEFAULT_TIMEOUT);
    }


    /**
     * Main wrapper method for sending data - (for getting responses)
     *
     * @param commandType
     * @param bodyData
     * @param timeoutMs
     * @return
     */
    private PumpMessage sendAndGetResponse(MedtronicCommandType commandType, byte[] bodyData, int timeoutMs) {
        // wakeUp
        wakeUp(receiverDeviceAwakeForMinutes, false);

        MedtronicUtil.setPumpDeviceState(PumpDeviceState.Active);

        // create message
        PumpMessage msg;

        if (bodyData == null)
            msg = makePumpMessage(commandType);
        else
            msg = makePumpMessage(commandType, bodyData);

        // send and wait for response
        PumpMessage response = sendAndListen(msg, timeoutMs);

        MedtronicUtil.setPumpDeviceState(PumpDeviceState.Sleeping);

        return response;
    }


    // FIXME remove
    // private PumpMessage sendAndGetACK(MedtronicCommandType commandType, byte[] bodyData, int timeoutMs) {
    // // wakeUp
    // wakeUp(receiverDeviceAwakeForMinutes, false);
    //
    // // create message
    // PumpMessage msg;
    //
    // if (bodyData == null)
    // msg = makePumpMessage(commandType);
    // else
    // msg = makePumpMessage(commandType, bodyData);
    //
    // // send and wait for ACK
    // PumpMessage response = send(msg, timeoutMs);
    // return response;
    // }

    protected PumpMessage sendAndListen(RLMessage msg) {
        return sendAndListen(msg, 4000); // 2000
    }


    // All pump communications go through this function.
    protected PumpMessage sendAndListen(RLMessage msg, int timeout_ms) {
        return sendAndListen(msg, timeout_ms, PumpMessage.class);
    }


    private Object sendAndGetResponseWithCheck(MedtronicCommandType commandType) {

        return sendAndGetResponseWithCheck(commandType, null);
    }


    private Object sendAndGetResponseWithCheck(MedtronicCommandType commandType, byte[] bodyData) {

        for (int retries = 0; retries < MAX_COMMAND_RETRIES; retries++) {

            PumpMessage response = sendAndGetResponse(commandType, bodyData, DEFAULT_TIMEOUT
                + (DEFAULT_TIMEOUT * retries));

            String check = checkResponseContent(response, commandType.commandDescription, commandType.expectedLength);

            if (check == null) {

                Object dataResponse = medtronicConverter.convertResponse(commandType, response.getRawContent());

                LOG.debug("Converted response for {} is {}.", commandType.name(), dataResponse);

                return dataResponse;
            } else {
                this.errorMessage = check;
                // return null;
            }

        }

        return null;
    }


    private String checkResponseContent(PumpMessage response, String method, int expectedLength) {

        if (!response.isValid()) {
            String responseData = String.format("%s: Invalid response.", method);
            LOG.warn(responseData);
            return responseData;
        }

        byte[] contents = response.getRawContent();

        if (contents != null) {
            if (contents.length >= expectedLength) {
                LOG.trace("{}: Content: {}", method, HexDump.toHexStringDisplayable(contents));
                return null;

            } else {
                String responseData = String.format(
                    "%s: Cannot return data. Data is too short [expected=%s, received=%s].", method, ""
                        + expectedLength, "" + contents.length);

                LOG.warn(responseData);
                return responseData;
            }
        } else {
            String responseData = String.format("%s: Cannot return data. Null response.", method);
            LOG.warn(responseData);
            return responseData;
        }
    }


    // TODO remove not needed - probably
    // @Deprecated
    // private void executeSetCommand(MedtronicCommandType commandType, byte[] bodyData) {
    //
    // LOG.debug("Executing Set for {} - 1st call", commandType.name());
    //
    // // first we send command without paramters and wait for ACK
    // PumpMessage pumpMessage = sendAndGetACK(commandType, null, 4000);
    //
    // // FIXME check if ACK
    // LOG.debug("Response 1 - {}", HexDump.toHexStringDisplayable(pumpMessage.getRawContent()));
    //
    //
    // LOG.debug("Executing Set for {} - 2nd call", commandType.name());
    // // second we send command with parameters and full package 64 bits with zeroed empty places
    //
    // byte newBodyData[] = new byte[64];
    // for(int i = 0; i < 64; i++) {
    // newBodyData[i] = 0x00;
    // }
    //
    // newBodyData[0] = (byte) bodyData.length;
    //
    // for(int i = 0; i < bodyData.length; i++) {
    // newBodyData[i + 1] = bodyData[i];
    // }
    //
    // PumpMessage pumpMessage2 = sendAndGetACK(commandType, newBodyData, 4000);
    //
    //
    // LOG.debug("Response 2 - {}", HexDump.toHexStringDisplayable(pumpMessage.getRawContent()));
    //
    // }

    // PUMP SPECIFIC COMMANDS

    public Float getRemainingInsulin() {

        Object responseObject = sendAndGetResponseWithCheck(MedtronicCommandType.GetRemainingInsulin);

        return responseObject == null ? null : (Float)responseObject;
    }


    public MedtronicDeviceType getPumpModel() {

        Object responseObject = sendAndGetResponseWithCheck(MedtronicCommandType.PumpModel);

        if (!MedtronicUtil.isModelSet()) {
            MedtronicUtil.setMedtronicPumpModel((MedtronicDeviceType)responseObject);
        }

        return responseObject == null ? null : (MedtronicDeviceType)responseObject;
    }


    public BasalProfile getBasalProfile_Old() {

        Object responseObject = sendAndGetResponseWithCheck(MedtronicCommandType.GetBasalProfileA);

        return responseObject == null ? null : (BasalProfile)responseObject;
    }


    public BasalProfile getBasalProfile() {

        // wakeUp
        wakeUp(receiverDeviceAwakeForMinutes, false);

        MedtronicUtil.setPumpDeviceState(PumpDeviceState.Active);

        MedtronicCommandType commandType = MedtronicCommandType.GetBasalProfileSTD;

        for (int retries = 0; retries <= MAX_COMMAND_RETRIES; retries++) {

            // create message
            PumpMessage msg;

            // if (bodyData == null)
            msg = makePumpMessage(commandType);

            // send and wait for response
            PumpMessage response = sendAndListen(msg, DEFAULT_TIMEOUT + (DEFAULT_TIMEOUT * retries));

            String check = checkResponseContent(response, commandType.commandDescription, commandType.expectedLength);

            byte[] data = null;

            int runs = 1;

            if (check == null) {

                data = response.getRawContent();

                PumpMessage ackMsg = makePumpMessage(MedtronicCommandType.CommandACK, new PumpAckMessageBody());

                while (checkIfWeHaveMoreData(commandType, response, data)) {

                    runs++;

                    PumpMessage response2 = sendAndListen(ackMsg, DEFAULT_TIMEOUT + (DEFAULT_TIMEOUT * retries));

                    // LOG.debug("{} Response: {}", runs, HexDump.toHexStringDisplayable(response2.getRawContent()));
                    // LOG.debug("{} Response: {}", runs,
                    // HexDump.toHexStringDisplayable(response2.getMessageBody().getTxData()));

                    String check2 = checkResponseContent(response2, commandType.commandDescription,
                        commandType.expectedLength);

                    if (check2 == null) {

                        data = ByteUtil.concat(data, ByteUtil.substring(response2.getMessageBody().getTxData(), 1));

                    } else {
                        this.errorMessage = check;
                    }
                }

            } else {
                errorMessage = check;
            }

            Object dataResponse = medtronicConverter.convertResponse(commandType, data);

            LOG.debug("Converted response for {} is {}.", commandType.name(), dataResponse);

            MedtronicUtil.setPumpDeviceState(PumpDeviceState.Sleeping);

            return (BasalProfile)dataResponse;
        }

        return null;

    }


    private boolean checkIfWeHaveMoreData(MedtronicCommandType commandType, PumpMessage response, byte[] data) {

        if (commandType == MedtronicCommandType.GetBasalProfileSTD || //
            commandType == MedtronicCommandType.GetBasalProfileA || //
            commandType == MedtronicCommandType.GetBasalProfileB) {
            byte[] responseRaw = response.getRawContent();

            int last = responseRaw.length - 1;

            LOG.debug("Length: " + data.length);

            if (data.length >= BasalProfile.MAX_RAW_DATA_SIZE) {
                return false;
            }

            if (responseRaw.length == 1) {
                return false;
            }

            return !(responseRaw[last] == 0x00 && responseRaw[last - 1] == 0x00 && responseRaw[last - 2] == 0x00);
        }

        return false;
    }


    public LocalDateTime getPumpTime() {

        Object responseObject = sendAndGetResponseWithCheck(MedtronicCommandType.RealTimeClock);

        return responseObject == null ? null : (LocalDateTime)responseObject;
    }


    public TempBasalPair getTemporaryBasal() {

        Object responseObject = sendAndGetResponseWithCheck(MedtronicCommandType.ReadTemporaryBasal);

        return responseObject == null ? null : (TempBasalPair)responseObject;
    }


    public Map<String, PumpSettingDTO> getPumpSettings() {

        Object responseObject = sendAndGetResponseWithCheck(MedtronicCommandType.getSettings(MedtronicUtil
            .getMedtronicPumpModel()));

        return responseObject == null ? null : (Map<String, PumpSettingDTO>)responseObject;
    }


    public Boolean setBolus(double units) {

        LOG.warn("setBolus: " + units);

        wakeUp(false);

        byte[] body = MedtronicUtil.getBolusStrokes(units);

        if (debugSetCommands)
            LOG.debug("Set Bolus: Body - {}", HexDump.toHexStringDisplayable(body));

        PumpMessage msg = makePumpMessage(MedtronicCommandType.SetBolus, //
            new CarelinkLongMessageBody(body));

        PumpMessage pumpMessage = runCommandWithArgs(msg);

        if (debugSetCommands)
            LOG.debug("Set Bolus: {}", pumpMessage.getResponseContent());

        return pumpMessage.commandType == MedtronicCommandType.CommandACK;
    }


    // TODO WIP test
    public boolean setTBR(TempBasalPair tbr) {

        wakeUp(false);

        byte[] body = tbr.getAsRawData();

        if (debugSetCommands)
            LOG.debug("Set TBR: Body - {}", HexDump.toHexStringDisplayable(body));

        PumpMessage msg = makePumpMessage(MedtronicCommandType.SetTemporaryBasal, //
            new CarelinkLongMessageBody(tbr.getAsRawData()));

        PumpMessage pumpMessage = runCommandWithArgs(msg);

        if (debugSetCommands)
            LOG.debug("Set TBR: {}", pumpMessage.getResponseContent());

        return pumpMessage.commandType == MedtronicCommandType.CommandACK;
    }


    public boolean cancelTBR() {
        return setTBR(new TempBasalPair(0.0d, false, 0));
    }


    public BatteryStatusDTO getRemainingBattery() {

        Object responseObject = sendAndGetResponseWithCheck(MedtronicCommandType.GetBatteryStatus);

        return responseObject == null ? null : (BatteryStatusDTO)responseObject;
    }


    // FIXME --- After this line commands in development --- REMOVE THIS COMMANDS

    // TODO remove for AAPS
    public ISFTable getPumpISFProfile() {

        PumpMessage response = sendAndGetResponse(MedtronicCommandType.ReadInsulinSensitivities);

        ISFTable table = new ISFTable();
        table.parseFrom(response.getContents());
        return table;
    }


    // TODO remove for AAPS
    public PumpMessage getBolusWizardCarbProfile() {
        PumpMessage response = sendAndGetResponse(MedtronicCommandType.GetCarbohydrateRatios);

        return response;
    }


    // TODO test

    public PumpMessage getPumpState() {
        PumpMessage response = sendAndGetResponse(MedtronicCommandType.PumpState);

        byte[] data = response.getRawContent();

        LOG.debug("Pump State: {}", HexDump.toHexStringDisplayable(data));

        // 3 TBR running ?

        return null;
    }


    public PumpMessage getBolusStatus() {
        PumpMessage response = sendAndGetResponse(MedtronicCommandType.SetBolus, new byte[] { 0x03, 0x00, 0x00, 0x00 },
            4000);

        byte[] data = response.getRawContent();

        LOG.debug("Detect bolus: {}", HexDump.toHexStringDisplayable(data));

        // 3 TBR running ?

        return null;
    }


    // TODO generateRawData (check if it works correctly) and test
    public Boolean setBasalProfile(BasalProfile basalProfile) {

        // [RileyLinkDevice] ======================== Save Basal Profile ===========================
        // [PumpMessageSender] getResponse(PumpMessage(carelink, setBasalProfileStandard, 3 bytes, 1 bytes), 0,
        // 0.180000, 3)
        // [PeripheralManager+RileyLink] RL Send: 19050000000000000000b4030000a9659a6b19b199c555b2c000
        // [PeripheralManager+RileyLink] RL Recv(single): bb
        // [PeripheralManager+RileyLink] RileyLink response: PacketResponse(code:
        // RileyLinkBLEKit.ResponseCode.commandInterrupted, packet: nil)
        // [PeripheralManager+RileyLink] RL Recv(single): dd0dbca9659a6b19b156655534d500
        // [PumpMessageSender] getResponse(PumpMessage(carelink, setBasalProfileStandard, 3 bytes, 65 bytes), 0,
        // 0.180000, 3)
        // [PeripheralManager+RileyLink] RL Send:
        // 79050000000000000000b4030000a9659a6b19b199c571c9a555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555556000
        // 2018-06-30 15:03:13.333962-0500 Loop[24609:13622692] [PeripheralManager+RileyLink] RL Recv(single):
        // dd10bda9659a6b19b156655534d500
        // 2018-06-30 15:03:13.334927-0500 Loop[24609:13622484] [PumpMessageSender] getResponse(PumpMessage(carelink,
        // setBasalProfileStandard, 3 bytes, 65 bytes), 0, 0.180000, 3)
        // 2018-06-30 15:03:13.337923-0500 Loop[24609:13622484] [PeripheralManager+RileyLink] RL Send:
        // 79050000000000000000b4030000a9659a6b19b199c5725555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555ac000
        // 2018-06-30 15:03:14.114024-0500 Loop[24609:13622486] [PeripheralManager+RileyLink] RL Recv(single):
        // dd0ebea9659a6b19b156655534d500
        // 2018-06-30 15:03:14.115017-0500 Loop[24609:13622484] [PumpMessageSender] getResponse(PumpMessage(carelink,
        // setBasalProfileStandard, 3 bytes, 65 bytes), 0, 0.180000, 3)
        // 2018-06-30 15:03:14.117600-0500 Loop[24609:13622484] [PeripheralManager+RileyLink] RL Send:
        // 79050000000000000000b4030000a9659a6b19b199c6a355555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555570e000
        // 2018-06-30 15:03:15.644502-0500 Loop[24609:13622692] [PeripheralManager+RileyLink] RL Recv(single):
        // dd0ebfa9659a6b19b156655534d500
        // 2018-06-30 15:03:15.645388-0500 Loop[24609:13622484] [RileyLinkDevice] ------------------------ Save Basal
        // Profile ---------------------------

        // 2018-06-30 15:03:12.167767-0500 Loop[24609:13622484] [RileyLinkDevice] ======================== Save Basal
        // Profile ===========================
        // 2018-06-30 15:03:12.168652-0500 Loop[24609:13622484] [PumpMessageSender] getResponse(PumpMessage(carelink,
        // setBasalProfileStandard, 3 bytes, 1 bytes), 0, 0.180000, 3)
        // 2018-06-30 15:03:12.169518-0500 Loop[24609:13622484] [PeripheralManager+RileyLink] RL Send:
        // 19050000000000000000b4030000a9659a6b19b199c555b2c000
        // 2018-06-30 15:03:12.463546-0500 Loop[24609:13622486] [PeripheralManager+RileyLink] RL Recv(single): bb
        // 2018-06-30 15:03:12.463954-0500 Loop[24609:13622486] [PeripheralManager+RileyLink] RileyLink response:
        // PacketResponse(code: RileyLinkBLEKit.ResponseCode.commandInterrupted, packet: nil)
        // 2018-06-30 15:03:12.554051-0500 Loop[24609:13622486] [PeripheralManager+RileyLink] RL Recv(single):
        // dd0dbca9659a6b19b156655534d500
        // 2018-06-30 15:03:12.555175-0500 Loop[24609:13622484] [PumpMessageSender] getResponse(PumpMessage(carelink,
        // setBasalProfileStandard, 3 bytes, 65 bytes), 0, 0.180000, 3)
        // 2018-06-30 15:03:12.557953-0500 Loop[24609:13622484] [PeripheralManager+RileyLink] RL Send:
        // 79050000000000000000b4030000a9659a6b19b199c571c9a555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555556000
        // 2018-06-30 15:03:13.333962-0500 Loop[24609:13622692] [PeripheralManager+RileyLink] RL Recv(single):
        // dd10bda9659a6b19b156655534d500
        // 2018-06-30 15:03:13.334927-0500 Loop[24609:13622484] [PumpMessageSender] getResponse(PumpMessage(carelink,
        // setBasalProfileStandard, 3 bytes, 65 bytes), 0, 0.180000, 3)
        // 2018-06-30 15:03:13.337923-0500 Loop[24609:13622484] [PeripheralManager+RileyLink] RL Send:
        // 79050000000000000000b4030000a9659a6b19b199c5725555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555ac000
        // 2018-06-30 15:03:14.114024-0500 Loop[24609:13622486] [PeripheralManager+RileyLink] RL Recv(single):
        // dd0ebea9659a6b19b156655534d500
        // 2018-06-30 15:03:14.115017-0500 Loop[24609:13622484] [PumpMessageSender] getResponse(PumpMessage(carelink,
        // setBasalProfileStandard, 3 bytes, 65 bytes), 0, 0.180000, 3)
        // 2018-06-30 15:03:14.117600-0500 Loop[24609:13622484] [PeripheralManager+RileyLink] RL Send:
        // 79050000000000000000b4030000a9659a6b19b199c6a355555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555555570e000
        // 2018-06-30 15:03:15.644502-0500 Loop[24609:13622692] [PeripheralManager+RileyLink] RL Recv(single):
        // dd0ebfa9659a6b19b156655534d500
        // 2018-06-30 15:03:15.645388-0500 Loop[24609:13622484] [RileyLinkDevice] ------------------------ Save Basal
        // Profile ---------------------------

        // byte[] body = basalProfile.generateRawData();

        byte[] body = new byte[] { 32, 0, 0, 38, 0, 13, 44, 0, 19, 38, 0, 28 };

        PumpMessage responseMessage;

        if (debugSetCommands)
            LOG.debug("Set Basal Profile: Body [{}] - {}", body.length, HexDump.toHexStringDisplayable(body));

        // if (body.length <= 64) {
        //
        // PumpMessage msg = makePumpMessage(MedtronicCommandType.SetBasalProfileA, //
        // new CarelinkLongMessageBody(ByteUtil.concat((byte) body.length, body)));
        //
        // responseMessage = runCommandWithArgs(msg);
        // } else
        {

            responseMessage = runCommandWithArgsLong(MedtronicCommandType.SetBasalProfileA, body);
        }

        if (debugSetCommands)
            LOG.debug("Set Basal Profile: {}", HexDump.toHexStringDisplayable(responseMessage.getRawContent()));

        return responseMessage.commandType == MedtronicCommandType.CommandACK;

    }


    public byte[] getFullMessageBody(byte[] bodyData, int length) {
        byte newBodyData[] = getEmptyMessage(length);

        newBodyData[0] = (byte)bodyData.length;

        for (int i = 0; i < bodyData.length; i++) {
            newBodyData[i + 1] = bodyData[i];
        }

        return newBodyData;
    }


    public byte[] getEmptyMessage(int length) {
        byte newBodyData[] = new byte[length];
        for (int i = 0; i < length; i++) {
            newBodyData[i] = 0x00;
        }

        return newBodyData;
    }


    public PumpMessage cancelBolus() {
        // ? maybe suspend and resume
        return null;
    }


    public PumpMessage setExtendedBolus(double units, int duration) {
        // FIXME see decocare
        PumpMessage response = sendAndGetResponse(MedtronicCommandType.SetBolus, MedtronicUtil.getBolusStrokes(units));

        return response;
    }


    public PumpMessage cancelExtendedBolus() {
        // set cancelBolus
        return null;
    }


    // Set TBR 100%
    // Cancel TBR (set TBR 100%) 100%
    // Get Status (40%)

    // Set Bolus 100%
    // Set Extended Bolus 20%
    // Cancel Bolus 0% -- NOT SUPPORTED
    // Cancel Extended Bolus 0% -- NOT SUPPORTED

    // Get Basal Profile (0x92) Read STD 100%
    // Set Basal Profile 0% -- NOT SUPPORTED
    // Read History 60%
    // Load TDD ?

    public void updatePumpManagerStatus() {
        // Integer resp = getRemainingBattery();
        // pumpStatus.batteryRemaining = resp == null ? -1 : resp;

        pumpStatus.reservoirRemainingUnits = getRemainingInsulin();

        /* current basal */
        // TempBasalPair basalRate = getCurrentBasalRate();

        // FIXME
        // byte[] basalRateBytes = resp.getContents();
        // if (basalRateBytes != null) {
        // if (basalRateBytes.length == 2) {
        // /**
        // * 0x98 0x06
        // * 0x98 is "basal rate"
        // * 0x06 is what? Not currently running a temp basal, current basal is "standard" at 0
        // */
        // double basalRate = ByteUtil.asUINT8(basalRateBytes[1]);
        // pumpStatus.currentBasal = basalRate;
        // }
        // }
        // get last bolus amount
        // get last bolus time
        // get tempBasalInProgress
        // get tempBasalRatio
        // get tempBasalRemainMin
        // get tempBasalStart
        // get pump time
        LocalDateTime clockResult = getPumpTime();
        if (clockResult != null) {
            // pumpStatus.time = clockResult.toDate();
        }
        // get last sync time

    }

}
