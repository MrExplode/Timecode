package me.mrexplode.timecode.gui;

import java.net.InetAddress;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class NetEntry {
    
    private InetAddress networkAddress;
    private String name;
    
    @Override
    public String toString() {
        return name;
    }

}
