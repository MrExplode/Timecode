package me.mrexplode.showmanager.music;

import lombok.extern.slf4j.Slf4j;
import me.mrexplode.showmanager.WorkerThread;
import me.mrexplode.showmanager.eventsystem.EventCall;
import me.mrexplode.showmanager.eventsystem.Listener;
import me.mrexplode.showmanager.eventsystem.events.music.MusicPauseEvent;
import me.mrexplode.showmanager.eventsystem.events.music.MusicStartEvent;
import me.mrexplode.showmanager.eventsystem.events.music.MusicStopEvent;
import me.mrexplode.showmanager.eventsystem.events.time.TimecodeChangeEvent;
import me.mrexplode.showmanager.eventsystem.events.time.TimecodePauseEvent;
import me.mrexplode.showmanager.eventsystem.events.time.TimecodeSetEvent;
import me.mrexplode.showmanager.eventsystem.events.time.TimecodeStartEvent;
import me.mrexplode.showmanager.eventsystem.events.time.TimecodeStopEvent;
import me.mrexplode.showmanager.fileio.Music;
import me.mrexplode.showmanager.gui.general.TrackPanel;
import me.mrexplode.showmanager.util.Timecode;
import me.mrexplode.showmanager.util.Utils;

import javax.sound.sampled.*;
import javax.swing.*;
import java.io.*;
import java.util.List;

@Slf4j
public class MusicThread implements Runnable, Listener {
    private final TrackPanel trackPanel;
    private JLabel infoLabel;
    private final Mixer mixer;
    private AudioInputStream audioStream;
    private AudioFormat format;
    private Clip currentClip;
    private final List<Music> trackList;
    private Tracker tracker;
    private int played = 0;
    private boolean running = true;
    private boolean playing = false;
    private boolean enabled = false;
    private final Object lock = new Object();
    
    public MusicThread(Mixer mixer, TrackPanel trackPanel, JLabel infoLabel, List<Music> musicList) {
        this.mixer = mixer;
        this.trackPanel = trackPanel;
        this.infoLabel = infoLabel;
        this.trackList = musicList;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("MusicThread");
        log.info("Starting...");
        
        //preload first
        loadTrack(played);
        
        long time = 0;
        while (running) {
            //slow it down. the lock gets notified on every frame change, and thats a bit too fast just to update the progressbar
            if (System.currentTimeMillis() >= time + 100) {
                time = System.currentTimeMillis();
                if (currentClip != null && currentClip.isRunning()) {
                    playing =  true;
                    /*SwingUtilities.invokeLater(() -> {
                        infoLabel.setText("Current track: " + new File(trackList.get(played).file).getName());
                    });*/
                } else {
                    playing = false;
                    /*SwingUtilities.invokeLater(() -> {
                        infoLabel.setText("Current track");
                    });*/
                    
                }
                if (playing) {
                    int pos = currentClip.getFramePosition();
                    int frameLength = currentClip.getFrameLength();
                    double progress = (double) pos / frameLength * 1000;
                    //trackPanel.setValue((int) Math.round(progress));
                    int rounded = (int) Math.round(progress);
                    //send music progress on redis
                    progressBarUpdate(trackPanel, rounded);
                }
            }

            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    shutdown();
                }
            }
        }
    }

    public void tick() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }
    
    /**
     * 
     * @param volume volume between 0.0f and 1.0f
     */
    public void setVolume(float volume) {
        FloatControl floatControl = (FloatControl) currentClip.getControl(FloatControl.Type.MASTER_GAIN);
        floatControl.setValue(20f * (float) Math.log10(volume));
    }
    
    public void setEnabled(boolean value) {
        enabled = value;
    }
    
    public Tracker getTracker() {
        return this.tracker;
    }
    
    private static void progressBarUpdate(TrackPanel panel, int val) {
        SwingUtilities.invokeLater(() -> {
            panel.setValue(val);
            panel.repaint();
        });
    }

    private void loadTrack(int index) {
        if (trackList.size() == 0)
            return;
        log.info("Sampling started for track " + index);
        long sampleTime = System.currentTimeMillis();
        float[] samples;
        try {
            samples = Utils.sampler(new File(trackList.get(index).getFile()));
            trackPanel.setSamples(samples);
            sampleTime = System.currentTimeMillis() - sampleTime;
        } catch (UnsupportedAudioFileException | IOException e) {
            Utils.displayError("Failed to sample the upcoming track: " + trackList.get(index).getFile() + "\n" + e.getMessage());
            log.error("Failed to sample track " + index, e);
        }
        try {
            if (currentClip != null) {
                currentClip.stop();
                currentClip.close();
                currentClip = null;
            }
            InputStream in = new BufferedInputStream(new FileInputStream(new File(trackList.get(index).getFile())));
            audioStream = AudioSystem.getAudioInputStream(in);
            format = audioStream.getFormat();
            if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
                format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, format.getSampleRate(), format.getSampleSizeInBits() * 2, format.getChannels(), format.getFrameSize() * 2, format.getFrameRate(), true); // big endian
                audioStream = AudioSystem.getAudioInputStream(format, audioStream);
            }
            SourceDataLine.Info sourceInfo = new DataLine.Info(Clip.class, format, ((int) audioStream.getFrameLength() * format.getFrameSize()));
            currentClip = (Clip) mixer.getLine(sourceInfo);
            currentClip.flush();
            currentClip.open(audioStream);
        } catch (IOException | UnsupportedAudioFileException | LineUnavailableException e1) {
            Utils.displayError("Failed to load the upcoming track: " + trackList.get(index).getFile() + "\n" + e1.getMessage());
            log.error("Loading failed", e1);
        }
        
        currentClip.addLineListener(e -> {
            if (e.getType() == LineEvent.Type.STOP) {
                if (tracker.isNaturalEnd()) {
                    currentClip.close();
                    currentClip = null;
                    played++;
                    if (played < trackList.size()) {
                        //when clip stops, load next
                        loadTrack(played);
                    }
                }
            }
        });
        
        Timecode end = trackList.get(index).getStartingTime().add(new Timecode(currentClip.getMicrosecondLength() / 1000));
        tracker = new Tracker(index, trackList.get(index).getStartingTime(), end, false);

        //old networking
//        long netTime = System.currentTimeMillis();
//        ArrayList<ArraySegment> segments = (ArrayList<ArraySegment>) Sequencer.sequence(samples, maxSegmentSize);
//        for (ArraySegment segment : segments) {
//            try {
//                float[] transportData = new float[2 + segment.getData().length];
//                transportData[0] = segment.getId();
//                transportData[1] = segment.getMax();
//                System.arraycopy(segment.getData(), 0, transportData, 2, segment.getData().length);
//                net.broadcastData(transportData);
//            } catch (IOException e1) {
//                err("Failed to send waveform sequence " + segment.getId() + " of " + segment.getMax());
//                e1.printStackTrace();
//            }
//        }
//        netTime = System.currentTimeMillis() - netTime;
//
//        MusicEvent event = new MusicEvent(EventType.MUSIC_LOAD, samples, trackList.get(index));
//        DataGrabber.getEventHandler().callEvent(event);
//
//        log("Loaded file in " + (System.currentTimeMillis() - time) + " ms, sampling took " + sampleTime + " ms, networking took " + netTime + " ms");
    }
    
    public void shutdown() {
        running = false;
        playing = false;
        if (currentClip != null) {
            currentClip.stop();
            currentClip.close();
            currentClip = null;
        }
        if (audioStream != null) {
            try {
                audioStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        mixer.close();
        synchronized (lock) {
            lock.notify();
        }
    }

    @EventCall
    void onTimeChange(TimecodeChangeEvent e) {
        if (!enabled) return;
        for (int i = 0; i < trackList.size(); i++) {
            if (trackList.get(i).getStartingTime().equals(e.getTime())) {
                if (i == played) {
                    MusicStartEvent event = new MusicStartEvent();
                    event.call(WorkerThread.getInstance().getEventBus());
                    if (!event.isCancelled()) {
                        playing = true;
                        tracker.setNaturalEnd(true);
                        currentClip.start();
                    }
                }
            }
        }
    }

    @EventCall
    void onTimeStart(TimecodeStartEvent e) {
        if (enabled && currentClip != null && tracker.inTrack(e.getTime())) {
            MusicStartEvent event = new MusicStartEvent();
            event.call(WorkerThread.getInstance().getEventBus());
            if (!event.isCancelled()) {
                playing = true;
                tracker.setNaturalEnd(true);
                currentClip.start();
            }
        }
    }

    @EventCall
    void onTimeStop(TimecodeStopEvent e) {
        playing = false;
        trackPanel.setValue(0);
        if (currentClip != null) {
            MusicStopEvent event = new MusicStopEvent();
            event.call(WorkerThread.getInstance().getEventBus());
            if (!event.isCancelled()) {
                tracker.setNaturalEnd(false);
                currentClip.stop();
            }
        }
        played = 0;
        loadTrack(played);
    }

    @EventCall
    void onTimePause(TimecodePauseEvent e) {
        playing = false;
        if (currentClip != null) {
            MusicPauseEvent event = new MusicPauseEvent();
            event.call(WorkerThread.getInstance().getEventBus());
            if (!event.isCancelled()) {
                tracker.setNaturalEnd(false);
                currentClip.stop();
            }
        }
    }

    @EventCall
    void onTimeSet(TimecodeSetEvent e) {
        if (tracker.inTrack(e.getTime())) {
            currentClip.setMicrosecondPosition(e.getTime().subtract(tracker.getStart()).millis() * 1000);
        } else {
            boolean preloaded = true;
            for (int i = 0; i < trackList.size(); i++) {
                Timecode start = trackList.get(i).getStartingTime();
                Timecode end = start.add(new Timecode(trackList.get(i).getLength()));
                Timecode current = e.getTime();
                played = i;
                if (current.compareTo(start) >= 0 && current.compareTo(end) <= 0) {
                    preloaded = false;
                    loadTrack(played);
                    currentClip.setMicrosecondPosition(current.subtract(start).millis() * 1000);
                    break;
                }
            }
            if (preloaded)
                loadTrack(played);
        }
    }

    @EventCall
    void onMusicStart(MusicStartEvent e) {
        trackPanel.setColor(TrackPanel.playColor);
    }

    @EventCall
    void onMusicStop(MusicStopEvent e) {
        trackPanel.setColor(TrackPanel.pauseColor);
    }

    @EventCall
    void onMusicPause(MusicPauseEvent e) {
        trackPanel.setColor(TrackPanel.pauseColor);
    }
}
