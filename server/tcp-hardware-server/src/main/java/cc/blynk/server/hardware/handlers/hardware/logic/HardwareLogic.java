package cc.blynk.server.hardware.handlers.hardware.logic;

import cc.blynk.server.Holder;
import cc.blynk.server.core.dao.ReportingDao;
import cc.blynk.server.core.dao.SessionDao;
import cc.blynk.server.core.model.DashBoard;
import cc.blynk.server.core.model.auth.Session;
import cc.blynk.server.core.model.enums.PinType;
import cc.blynk.server.core.processors.EventorProcessor;
import cc.blynk.server.core.processors.WebhookProcessor;
import cc.blynk.server.core.protocol.exceptions.IllegalCommandException;
import cc.blynk.server.core.protocol.model.messages.StringMessage;
import cc.blynk.server.core.session.HardwareStateHolder;
import cc.blynk.utils.ParseUtil;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static cc.blynk.server.core.protocol.enums.Command.HARDWARE;
import static cc.blynk.utils.StringUtils.BODY_SEPARATOR_STRING;
import static cc.blynk.utils.StringUtils.split3;

/**
 * Handler responsible for forwarding messages from hardware to applications.
 * Also handler stores all incoming hardware commands to disk in order to export and
 * analyze data.
 *
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 2/1/2015.
 *
 */
public class HardwareLogic {

    private static final Logger log = LogManager.getLogger(HardwareLogic.class);

    private final ReportingDao reportingDao;
    private final SessionDao sessionDao;
    private final EventorProcessor eventorProcessor;
    private final WebhookProcessor webhookProcessor;

    public HardwareLogic(Holder holder) {
        this.sessionDao = holder.sessionDao;
        this.reportingDao = holder.reportingDao;
        this.eventorProcessor = holder.eventorProcessor;
        this.webhookProcessor = new WebhookProcessor(holder.asyncHttpClient,
                holder.props.getLongProperty("webhooks.frequency.user.quota.limit", 1000),
                holder.stats);
    }

    private static boolean isWriteOperation(String body) {
        return body.charAt(1) == 'w';
    }

    public void messageReceived(ChannelHandlerContext ctx, HardwareStateHolder state, StringMessage message) {
        Session session = sessionDao.userSession.get(state.user);

        final String body = message.body;

        //minimum command - "ar 1"
        if (body.length() < 4) {
            throw new IllegalCommandException("HardwareLogic command body too short.");
        }

        int dashId = state.dashId;
        DashBoard dash = state.user.profile.getDashByIdOrThrow(dashId);

        if (isWriteOperation(body)) {
            String[] splitBody = split3(body);

            if (splitBody.length < 3 || splitBody[0].length() == 0) {
                throw new IllegalCommandException("Write command is wrong.");
            }

            final PinType pinType = PinType.getPinType(splitBody[0].charAt(0));
            final byte pin = ParseUtil.parseByte(splitBody[1]);
            final String value = splitBody[2];

            if (value.length() == 0) {
                throw new IllegalCommandException("Hardware write command doesn't have value for pin.");
            }

            reportingDao.process(state.user.name, dashId, pin, pinType, value);

            dash.update(pin, pinType, value);

            //todo this temp catch. remove in next update.
            try {
                process(dash, session, pin, pinType, value);
            } catch (Exception e) {
                log.error("Error processing.", e);
            }
        }

        if (dash.isActive) {
            session.sendToApps(HARDWARE, message.id, dashId + BODY_SEPARATOR_STRING + body);
        } else {
            log.debug("No active dashboard.");
        }
    }

    private void process(DashBoard dash, Session session, byte pin, PinType pinType, String value) {
        eventorProcessor.process(session, dash, pin, pinType, value);
        webhookProcessor.process(dash, pin, pinType, value);
    }

}
