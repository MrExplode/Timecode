package me.mrexplode.timecode;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.function.Consumer;

public class Networking implements Runnable {
    
    private int port;
    private DatagramSocket socket;
    private InetAddress address;
    private boolean running = true;
    private Consumer<float[]> consumer;
    
    public Networking(int port) {
        this.port = port;
        try {
            this.socket = new DatagramSocket();
            this.address = InetAddress.getByName("255.255.255.255");
        } catch (UnknownHostException | SocketException e) {
            e.printStackTrace();
        }
    }
    
    public void broadcastData(float[] data) throws IOException {
        byte[] bytes = float2byteArray(data);
        DatagramPacket packet = new DatagramPacket(bytes, 0, bytes.length, address, port);
        socket.send(packet);
    }
    
    public void startListening(Consumer<float[]> consumer) {
        this.consumer = consumer;
        try {
            socket = new DatagramSocket(port, InetAddress.getByName("0.0.0.0"));
        } catch (SocketException | UnknownHostException e) {
            e.printStackTrace();
        }
        running = true;
        Thread t = new Thread(this);
        t.start();
    }
    
    @Override
    public void run() {
        Thread.currentThread().setName("NetworkClient");
        byte[] buffer = new byte[16000];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running) {
            try {
                socket.receive(packet);
                byte[] sized = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), 0, sized, 0, packet.getLength());
                float[] data = byte2floatArray(sized);
                consumer.accept(data);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    public void shutdown() {
        running = false;
        socket.close();
    }
    
    private static byte[] float2byteArray(float... args) {
        ByteBuffer buffer = ByteBuffer.allocate(4 * args.length);
        
        for (float f : args) {
            buffer.putFloat(f);
        }
        
        return buffer.array();
    }
    
    private static float[] byte2floatArray(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        FloatBuffer fb = buffer.asFloatBuffer();
        float[] fa = new float[fb.limit()];
        fb.get(fa);
        return fa;
    }

}