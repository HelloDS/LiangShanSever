package com.ruoyi.web.game;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import com.ruoyi.game.admin.AdminTimes;
import com.ruoyi.game.room.RoomExports;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 受控私有目录；仅服务端生成的 UUID 文件名，CSV 字节与行数都有上限。 */
@Component
public class RoomExportFiles {
    private final Path directory;
    static final List<String> COLUMNS=List.of("roomId","roomCode","gameId","ownerUid","creatorUid","clubId","status","isTest","roundNo","createdAt","endedAt","ruleVersion");
    public RoomExportFiles(@Value("${game.exports.directory:.local/exports}") String path){
        try{
            directory=Path.of(path).toAbsolutePath().normalize();
            for(Path current=directory;current!=null;current=current.getParent())if(Files.isSymbolicLink(current))throw new IOException("symlink");
            Files.createDirectories(directory,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("directory");
            Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        }catch(IOException failure){throw new IllegalStateException("导出目录不可用");}
    }
    private Path path(String id,String token,String suffix){RoomExports.id(id);RoomExports.id(token);return directory.resolve(id+"-"+token+suffix);}
    public Session begin(RoomExports.Lease lease) throws IOException {
        var file=path(lease.id(),lease.token(),".part");
        var channel=Files.newByteChannel(file,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{return new Session(file,path(lease.id(),lease.token(),".csv"),Channels.newOutputStream(channel));}
        catch(IOException failure){channel.close();throw failure;}
    }
    public final class Session implements AutoCloseable {
        private final Path partial,finished;private final OutputStream output;private int bytes,rows;private boolean closed;
        Session(Path partial,Path finished,OutputStream output) throws IOException {this.partial=partial;this.finished=finished;this.output=output;write("\uFEFF"+String.join(",",COLUMNS)+"\r\n");}
        private void write(String value) throws IOException {
            byte[] data=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if((long)bytes+data.length>RoomExports.MAX_BYTES)throw new ExportLimit();output.write(data);bytes+=data.length;
        }
        public void row(Map<String,Object> value) throws IOException {
            if(rows>=RoomExports.MAX_ROWS)throw new ExportLimit();Map<String,Object> timed=AdminTimes.utc(value);
            var fields=COLUMNS.stream().map(key->csv(timed.get(key))).toList();write(String.join(",",fields)+"\r\n");rows++;
        }
        public void finish() throws IOException {close();Files.move(partial,finished,StandardCopyOption.ATOMIC_MOVE);}
        public int rows(){return rows;}public int bytes(){return bytes;}
        @Override public void close() throws IOException {if(!closed){closed=true;output.close();}}
    }
    public static final class ExportLimit extends IOException {public ExportLimit(){super("导出内容超出容量");}}
    static String csv(Object value){
        String text=value==null?"":value.toString();int index=0;
        // 空格、制表符及控制字符前缀不能绕过公式检查；内容不拼接成公式。
        while(index<text.length()&&(Character.isWhitespace(text.charAt(index))||Character.isSpaceChar(text.charAt(index))||Character.isISOControl(text.charAt(index))||text.charAt(index)=='\uFEFF'||text.charAt(index)=='\u200B'))index++;
        // 使用可见文本标记，避免仅加单引号在表格另存 CSV 后被移除；包括全角符号。
        boolean controlPrefix=!text.isEmpty()&&"\t\r\n".indexOf(text.charAt(0))>=0;
        if(controlPrefix||(index<text.length()&&"=+-@＝＋－＠".indexOf(text.charAt(index))>=0))text="text:"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }
    public InputStream open(RoomExports.Download download) throws IOException {
        Path file=path(download.id(),download.token(),".csv");
        var channel=Files.newByteChannel(file,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
        try{if(download.bytes()<1||download.bytes()>RoomExports.MAX_BYTES||channel.size()!=download.bytes())throw new IOException("size");return Channels.newInputStream(channel);}
        catch(Exception failure){channel.close();throw failure;}
    }
    /** 只移除私有目录内、超过 25 小时的服务器文件；不递归，不跟随链接。 */
    public void clean(Instant now) throws IOException {
        int scanned=0;
        try(var files=Files.newDirectoryStream(directory)){
            for(Path file:files){if(++scanned>1000)throw new IOException("cleanup capacity");
                if(!file.getFileName().toString().matches("[0-9a-f-]{36}-[0-9a-f-]{36}\\.(part|csv)")||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;
                if(Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(now.minus(Duration.ofHours(25))))Files.delete(file);
            }
        }
    }
}
