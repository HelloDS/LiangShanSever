package com.ruoyi.game.lobby;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import java.util.*;
import javax.imageio.ImageIO;

/** 旧公告面板只读取 imageLink，以图片承载后台发布的正文，不修改客户端。 */
public final class NoticeArtwork {
    private NoticeArtwork() { }
    private static final String FONT=font();
    private static String font(){
        Set<String> available=Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for(String candidate:List.of("PingFang SC","Noto Sans CJK SC","Microsoft YaHei","SansSerif"))if(available.contains(candidate))return candidate;
        return "SansSerif";
    }
    public static void validate(String content){layout(content);}
    private static List<String> layout(String content){
        BufferedImage image=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();
        try{
            graphics.setFont(new Font(FONT,Font.PLAIN,23));FontMetrics metrics=graphics.getFontMetrics();
            List<String> lines=new ArrayList<>();StringBuilder current=new StringBuilder();
            for(int point:content.replace("\r\n","\n").codePoints().toArray()){
                if(point=='\n'){lines.add(current.toString());current.setLength(0);continue;}
                if(Character.isISOControl(point))throw new IllegalArgumentException("公告含有不支持的控制字符");
                String next=new String(Character.toChars(point));
                if(metrics.stringWidth(current+next)>480){lines.add(current.toString());current.setLength(0);}
                current.append(next);
            }
            lines.add(current.toString());
            if(lines.size()>17)throw new IllegalArgumentException("公告正文排版超过 17 行，请缩短正文后预览");
            return lines;
        }finally{graphics.dispose();}
    }
    public static byte[] render(String title,String content){
        List<String> lines=layout(content);BufferedImage image=new BufferedImage(560,640,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        try{
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0xfffef8));g.fillRect(0,0,560,640);g.setColor(new Color(0x357a63));g.fillRect(0,0,560,10);
            g.setFont(new Font(FONT,Font.BOLD,32));g.setColor(new Color(0x253e32));g.drawString(title,40,72);
            g.setColor(new Color(0xdce7df));g.fillRect(40,98,480,1);g.setFont(new Font(FONT,Font.PLAIN,23));g.setColor(new Color(0x42574c));
            for(int i=0;i<lines.size();i++)g.drawString(lines.get(i),40,142+i*28);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();
        }catch(IOException e){throw new UncheckedIOException(e);}finally{g.dispose();}
    }
}
