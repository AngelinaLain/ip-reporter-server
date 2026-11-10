package com.ipreporter.server;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;

public final class IpAddresses {
    private IpAddresses() {}
    // 只接受 IP 字面量，避免校验过程中触发 DNS 或阻塞解析。
    public static byte[] parse(String address,String type) {
        if(address==null) throw ApiException.bad("IP 地址不能为空");
        if(type.equals("A")) {
            String[] octets=address.split("\\.",-1);
            if(octets.length!=4) throw ApiException.bad("IPv4 地址格式错误");
            byte[] result=new byte[4];
            for(int i=0;i<4;i++) {
                if(!octets[i].matches("0|[1-9][0-9]{0,2}")) throw ApiException.bad("IPv4 地址格式错误");
                int value=Integer.parseInt(octets[i]);if(value>255) throw ApiException.bad("IPv4 地址格式错误");result[i]=(byte)value;
            }
            return result;
        }
        if(!type.equals("AAAA") || !address.contains(":") || !address.matches("[0-9a-fA-F:]{2,39}")) throw ApiException.bad("IPv6 地址格式错误");
        try { InetAddress result=InetAddress.getByName(address);if(!(result instanceof Inet6Address)) throw new IllegalArgumentException();return result.getAddress(); }
        catch(Exception error) { throw ApiException.bad("IPv6 地址格式错误"); }
    }
    public static boolean eligible(String address,String type,boolean allowPrivate) {
        byte[] b=parse(address,type);
        if(type.equals("A")) {
            int a=b[0]&255,c=b[1]&255,d=b[2]&255;
            if(a==0 || a==127 || a>=224 || (a==169 && c==254)) return false;
            if(allowPrivate) return true;
            return !(a==10 || (a==172 && c>=16 && c<=31) || (a==192 && c==168) || (a==100 && c>=64 && c<=127)
                || (a==192 && c==0 && d==0) || (a==192 && c==0 && d==2) || (a==198 && (c==18 || c==19))
                || (a==198 && c==51 && d==100) || (a==203 && c==0 && d==113));
        }
        int first=b[0]&255,second=b[1]&255;
        boolean zero=Arrays.equals(b,new byte[16]);
        boolean loopback=true;for(int i=0;i<15;i++) loopback&=b[i]==0;loopback&=b[15]==1;
        if(zero || loopback || first==255 || (first==254 && (second&192)==128)) return false;
        if(allowPrivate) return true;
        // 默认只发布全球单播，ULA、文档地址和过渡地址不会误写入公网 DNS。
        return (first&224)==32 && !(first==32 && second==1 && (b[2]&255)==13 && (b[3]&255)==184);
    }
}
