package me.mrexplode.showmanager.osc;

import com.illposed.osc.*;
import com.illposed.osc.transport.udp.OSCPortIn;
import com.illposed.osc.transport.udp.OSCPortOut;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.mrexplode.showmanager.WorkerThread;
import me.mrexplode.showmanager.eventsystem.events.osc.OscDispatchEvent;
import me.mrexplode.showmanager.eventsystem.events.osc.OscReceiveEvent;

import java.io.IOException;
import java.net.InetAddress;

@Slf4j
@Getter
@RequiredArgsConstructor
public class OscHandler {
    private final InetAddress address;
    private final int outgoingPort;
    private final int incomingPort;

    private OSCPortOut portOut;
    private OSCPortIn portIn;

    public void setup() throws IOException {
        log.info("Starting OSCHandler...");
        portOut = new OSCPortOut(address, outgoingPort);
        portIn = new OSCPortIn(incomingPort);
        portIn.addPacketListener(new OSCPacketListener() {
            @Override
            public void handlePacket(OSCPacketEvent event) {
                if (event.getPacket() instanceof OSCMessage && ((OSCMessage) event.getPacket()).getAddress().startsWith("/timecode/")) {
                    OscReceiveEvent oscEvent = new OscReceiveEvent(event.getPacket());
                    oscEvent.call(WorkerThread.getInstance().getEventBus());
                }
            }

            @Override
            public void handleBadData(OSCBadDataEvent event) {
                log.warn("Received bad OSC data", event.getException());
            }
        });
    }

    public void shutdown() throws IOException {
        portOut.close();
        portIn.close();
    }

    public void sendOscPacket(OSCPacket packet) {
        OscDispatchEvent event = new OscDispatchEvent(packet);
        event.call(WorkerThread.getInstance().getEventBus());
        if (event.isCancelled())
            return;
        try {
            portOut.send(packet);
        } catch (IOException | OSCSerializeException e) {
            log.error("Failed to send OSC packet", e);
        }
    }
}
