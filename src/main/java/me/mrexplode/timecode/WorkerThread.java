package me.mrexplode.timecode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.swing.JOptionPane;

import com.illposed.osc.OSCMessage;
import com.illposed.osc.OSCSerializeException;
import com.illposed.osc.transport.udp.OSCPortOut;

import ch.bildspur.artnet.ArtNetBuffer;
import ch.bildspur.artnet.ArtNetException;
import ch.bildspur.artnet.ArtNetServer;
import ch.bildspur.artnet.PortDescriptor;
import ch.bildspur.artnet.events.ArtNetServerEventAdapter;
import ch.bildspur.artnet.packets.ArtDmxPacket;
import ch.bildspur.artnet.packets.ArtNetPacket;
import ch.bildspur.artnet.packets.ArtPollReplyPacket;
import ch.bildspur.artnet.packets.ArtTimePacket;
import ch.bildspur.artnet.packets.PacketType;
import me.mrexplode.ltc4j.Framerate;
import me.mrexplode.ltc4j.LTCGenerator;
import me.mrexplode.timecode.events.EventType;
import me.mrexplode.timecode.events.OscEvent;
import me.mrexplode.timecode.events.TimeChangeEvent;
import me.mrexplode.timecode.events.TimeEvent;
import me.mrexplode.timecode.gui.SchedulerTableModel;
import me.mrexplode.timecode.schedule.OSCDataType;
import me.mrexplode.timecode.schedule.ScheduledEvent;
import me.mrexplode.timecode.schedule.ScheduledOSC;

public class WorkerThread implements Runnable {
    
    //artnet
    private ArtNetServer server;
    private ArtTimePacket packet;
    private ArtNetBuffer artBuffer;
    private InetAddress artnetAddress;
    
    //ltc
    private Mixer mixer = null;
    private LTCGenerator ltcGenerator = null;
    
    //OSC
    private OSCPortOut oscOut = null;
    private InetAddress oscAddress = null;
    private int oscPort = 0;
    private SchedulerTableModel model = null;
    
    //main controls
    private boolean running = true;
    private boolean broadcast = false;
    private boolean playLTC = false;
    private boolean sendOSC = false;
    private boolean playing = false;
    //start time of playing
    private long start = 0;
    private long elapsed = 0;
    /**
     * the timecode value, in milliseconds
     */
    private Timecode timecode;
    
    //remote
    private boolean remote = false;
    private RemoteState remoteState;
    private int dmxAddress = 1;
    private int universe = 0;
    private int subnet = 0;
    
    private static int framerate = 30;
    
    private Thread dataGrabberThread;
    private DataGrabber dataGrabber;
    private Object dataLock;
    
    public WorkerThread(Mixer mixer, InetAddress artnetAddress, SchedulerTableModel model, InetAddress oscAddress, int oscPort, Thread grabber, DataGrabber dataGrabber, Object dataLock) {
        this(mixer, artnetAddress, model, oscAddress, oscPort, grabber, dataGrabber, dataLock, null);
    }
    
    public WorkerThread(Mixer mixer, InetAddress artnetAddress, SchedulerTableModel model, InetAddress oscAddress, int oscPort, Thread grabber, DataGrabber dataGrabber, Object dataLock, ArtNetServer server) {
        this.mixer = mixer;
        this.model = model;
        this.oscAddress = oscAddress;
        this.oscPort = oscPort;
        this.artnetAddress = artnetAddress;
        this.server = (server == null ? new ArtNetServer() : server);
        this.packet = new ArtTimePacket();
        this.artBuffer = new ArtNetBuffer();
        this.remoteState = RemoteState.IDLE;
        this.dataGrabberThread = grabber;
        this.dataGrabber = dataGrabber;
        this.dataLock = dataLock;
        this.timecode = new Timecode(0);
    }

    @Override
    public void run() {
        log("Starting thread...");
        Thread.currentThread().setName("WorkerThread");
        running = true;
        artBuffer.clear();
        //input handling
        server.addListener(new ArtNetServerEventAdapter() {
            @Override
            public void artNetPacketReceived(ArtNetPacket packet) {
                if (packet.getType() != PacketType.ART_OUTPUT)
                    return;
                
                ArtDmxPacket dmxPacket = (ArtDmxPacket) packet;
                int subnet = dmxPacket.getSubnetID();
                int universe = dmxPacket.getUniverseID();

                artBuffer.setDmxData((short) subnet, (short) universe, dmxPacket.getDmxData());
            }
        });
        
        //artnet node discovery reply packet
        ArtPollReplyPacket replyPacket = new ArtPollReplyPacket();
        replyPacket.setIp(artnetAddress);
        replyPacket.setShortName("TimecodeGen Node");
        replyPacket.setLongName("Timecode Generator Node by MrExplode");
        replyPacket.setVersionInfo(1);
        replyPacket.setSubSwitch(1);
        replyPacket.setOemCode(5);
        PortDescriptor port = new PortDescriptor();
        port.setCanInput(true);
        port.setCanOutput(true);
        replyPacket.setPorts(new PortDescriptor[] {port});
        
        replyPacket.translateData();
        server.setDefaultReplyPacket(replyPacket);
        
        try {
            server.start(artnetAddress);
        } catch (SocketException | ArtNetException e) {
            err("Failed to start ArtNet server");
            displayError("Failed to start ArtNetServer: " + e.getMessage() + "\n Please restart the internals!");
            throw new RuntimeException("Failed to start WorkerThread.", e);
        }
        
        //setup the ltc
        Framerate frameInstance = null;
        if (framerate == 24)
            frameInstance = Framerate.FRAMERATE_24;
        if (framerate == 25)
            frameInstance = Framerate.FRAMERATE_25;
        if (framerate == 30)
            frameInstance = Framerate.FRAMERATE_30;
        ltcGenerator = new LTCGenerator(mixer, frameInstance);
        try {
            ltcGenerator.init();
        } catch (LineUnavailableException e1) {
            err("Failed to start ltc generator");
            displayError("Failed to start LTC generator: " + e1.getMessage() + "\n Please restart the internals!");
            throw new RuntimeException("Failed to start WorkerThread.", e1);
        }
        
        try {
            oscOut = new OSCPortOut(oscAddress, oscPort);
        } catch (IOException e) {
            err("Failed to start OSC server");
            displayError("Failed to start OSC server: " + e.getMessage() + "\n Please restart the internals!");
            e.printStackTrace();
        }
        
        start = 0;
        long time = start;
        while (running) {
            final long current = System.currentTimeMillis();
            if (current >= time + (1000 / framerate)) {
                time = current;
                
                if (playing) {
                    elapsed = time - start;
                }
                if (remote) {
                    byte[] data = artBuffer.getDmxData((short) subnet, (short) universe);
                    switch (data[dmxAddress - 1]) {
                        default:
                            remoteState = RemoteState.IDLE;
                            break;
                        case 25:
                            remoteState = RemoteState.FORCE_IDLE;
                            break;
                        case 51:
                            remoteState = RemoteState.PLAYING;
                            play();
                            break;
                        case 76:
                            remoteState = RemoteState.PAUSE;
                            pause();
                            break;
                        case 102:
                            remoteState = RemoteState.STOPPED;  
                            stop();
                            break;
                    }
                } else {
                    remoteState = RemoteState.DISABLED;
                }
                
                if (playing) {
                    timecode = new Timecode(elapsed, framerate);
                    packet.setTime(timecode.getHour(), timecode.getMin(), timecode.getSec(), timecode.getFrame());
                    ltcGenerator.setTime(timecode.getHour(), timecode.getMin(), timecode.getSec(), timecode.getFrame());
                }
                
                if (dataGrabberThread.isAlive()) {
                    synchronized (dataLock) {
                        this.dataLock.notify();
                    }
                }
                
                dataGrabber.update();
                
                if (playing) {
                  //TODO: move to datagrabber
                    //OSC stuff. idk about performance, but it might need to be moved from here if it slows down the loop
                    if (sendOSC) {
                        ArrayList<ScheduledEvent> events = (ArrayList<ScheduledEvent>) model.getCurrentFor(getCurrentTimecode());
                        if (events != null) {
                            for (int i = 0; i < events.size(); i++) {
                                if (events.get(i) instanceof ScheduledOSC) {
                                    ScheduledOSC oscMessage = (ScheduledOSC) events.get(i);
                                    if (oscMessage.isReady()) {
                                        try {
                                            OSCMessage oscPacket = new OSCMessage(oscMessage.getPath(), Collections.singletonList(OSCDataType.castTo(oscMessage.getValue(), oscMessage.getDataType())));
                                            oscOut.send(oscPacket);
                                            DataGrabber.getEventHandler().callEvent(new OscEvent(EventType.OSC_DISPATCH, oscPacket));
                                            System.err.println("sent osc message " + oscMessage.getPath());
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        } catch (OSCSerializeException e) {
                                            displayError("Failed to serialize osc message: " + oscMessage.getPath());
                                            e.printStackTrace();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (broadcast) {
                    server.broadcastPacket(packet);
                }
            } 
        }
    }
    
    public Timecode getCurrentTimecode() {
        return timecode;
    }
    
    public String getCurrentTime() {
        return getCurrentTimecode().guiFormatted();
    }
    
    public void setTime(Timecode time) {
        //FIXME: something
        packet.setTime(time.getHour(), time.getMin(), time.getSec(), time.getFrame());
        ltcGenerator.setTime(time.getHour(), time.getMin(), time.getSec(), time.getFrame());
        elapsed = time.millis();
        start = System.currentTimeMillis() - elapsed;
        TimeEvent event = new TimeEvent(EventType.TC_SET);
        event.setAdditionalValue(getCurrentTimecode());
        DataGrabber.getEventHandler().callEvent(event);
        DataGrabber.getEventHandler().callEvent(new TimeChangeEvent(getCurrentTimecode()));
    }
    
    public void play() {
        //starting first
        if (start == 0) {
            start = System.currentTimeMillis();
        } else {
            start = System.currentTimeMillis() - elapsed;
        }
        if (playLTC) {
            ltcGenerator.start();
        }
        this.playing = true;
        TimeEvent event = new TimeEvent(EventType.TC_START);
        event.setAdditionalValue(getCurrentTimecode());
        DataGrabber.getEventHandler().callEvent(event);
    }
    
    public boolean isPlaying() {
        return this.playing;
    }
    
    public void pause() {
        this.playing = false;
        if (playLTC) {
            ltcGenerator.stop();
        }
        TimeEvent event = new TimeEvent(EventType.TC_PAUSE);
        event.setAdditionalValue(getCurrentTimecode());
        DataGrabber.getEventHandler().callEvent(event);
    }
    
    public void stop() {
        this.playing = false;
        if (playLTC) {
            ltcGenerator.setTime(0, 0, 0, 0);
            ltcGenerator.stop();
        }
        timecode = new Timecode(0);
        start = 0;
        
        TimeEvent event = new TimeEvent(EventType.TC_STOP);
        event.setAdditionalValue(getCurrentTimecode());
        DataGrabber.getEventHandler().callEvent(event);
    }
    
    public void shutdown() {
        log("Shutting down...");
        running = false;
        ltcGenerator.shutdown();
        mixer.close();
        mixer = null;
        server.stop();
    }
    
    public void setBroadcast(boolean value) {
        this.broadcast = value;
    }
    
    public void setLTC(boolean value) {
        this.playLTC = value;
    }
    
    public void setOSC(boolean value) {
        this.sendOSC = value;
    }
    
    public void setFramerate(int framerate) {
        if (framerate == 24 || framerate == 25 || framerate == 30) {
            WorkerThread.framerate = framerate;
        } else {
            throw new IllegalArgumentException("Not valid framerate! Framerate must be 24, 25 or 30");
        }
    }
    
    public static int getFramerate() {
        return framerate;
    }
    
    public void setRemoteControl(boolean mode) {
        this.remote = mode;
    }
    
    public RemoteState getRemoteState() {
        return remoteState;
    }
    
    public int getDmxAddress() {
        return dmxAddress;
    }

    
    public void setDmxAddress(int dmxAddress) {
        if (dmxAddress < 1 || dmxAddress > 512) {
            throw new IllegalArgumentException("Dmx address must be between 1 and 512");
        } else {
            this.dmxAddress = dmxAddress;
        }
    }

    
    public int getUniverse() {
        return universe;
    }

    
    public void setUniverse(int universe) {
        this.universe = universe;
    }

    
    public int getSubnet() {
        return subnet;
    }

    
    public void setSubnet(int subnet) {
        this.subnet = subnet;
    }
    
    private static void displayError(String errorMessage) {
        Thread t = new Thread(() -> {
            JOptionPane.showConfirmDialog(null, errorMessage, "Timecode Generator", JOptionPane.OK_CANCEL_OPTION, JOptionPane.ERROR_MESSAGE, null);
        });
        t.setName("Error display thread");
        t.start();
    }
    
    private static void log(String message) {
        System.out.println("[WorkerThread] " + message);
    }
    
    private static void err(String errorMessage) {
        System.err.println("[WorkerThread] " + errorMessage);
    }

}