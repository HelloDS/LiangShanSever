package com.ruoyi.web.game;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import com.ruoyi.game.room.RoomExports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RoomExportFilesTest {
    @TempDir Path directory;
    private RoomExports.Lease lease(){return new RoomExports.Lease(UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,"hash",null,LocalDateTime.now());}
    @Test void csvQuotesCommasQuotesNewlinesAndNeutralizesFormulaPrefixes(){
        assertEquals("\"a,\"\"b\"\"\r\nc\"",RoomExportFiles.csv("a,\"b\"\r\nc"));
        for(String value:List.of("=1+1","+SUM(A1)","-1+2","@cmd"," \t=1","\r\n+1","\uFEFF=1","\u00a0@cmd","\u200B-1","＝1+1","＋1","－1","＠cmd","\t普通文本"))assertEquals("\"text:"+value+"\"",RoomExportFiles.csv(value));
        assertEquals("\"\"",RoomExportFiles.csv(null));assertEquals("\"普通文本\"",RoomExportFiles.csv("普通文本"));
    }
    @Test void generatedCsvIsPrivateHasOnlyWhitelistedColumnsAndCanBeReopened() throws Exception {
        var files=new RoomExportFiles(directory.toRealPath().toString());var lease=lease();int bytes;
        try(var output=files.begin(lease)){
            output.row(Map.of("roomId",lease.id(),"ruleVersion","=danger","snapshotJson","private-hand","createdAt",LocalDateTime.of(2026,11,1,0,0)));
            output.finish();bytes=output.bytes();assertEquals(1,output.rows());
        }
        try(var input=files.open(new RoomExports.Download(lease.id(),lease.token(),bytes))){
            String text=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.startsWith("\uFEFFroomId,roomCode"));assertTrue(text.contains("2026-11-01T00:00:00Z"));assertTrue(text.contains("text:=danger"));assertFalse(text.contains("private-hand"));assertFalse(text.contains("snapshotJson"));
        }
        Path csv=directory.resolve(lease.id()+"-"+lease.token()+".csv");
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(csv));assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(directory));
        assertThrows(java.io.IOException.class,()->files.open(new RoomExports.Download(lease.id(),lease.token(),bytes+1)));
    }
    @Test void rowAndByteLimitsDoNotExposePartialArtifacts() throws Exception {
        var files=new RoomExportFiles(directory.toRealPath().toString());var rows=lease();
        try(var output=files.begin(rows)){for(int i=0;i<RoomExports.MAX_ROWS;i++)output.row(Map.of());assertThrows(RoomExportFiles.ExportLimit.class,()->output.row(Map.of()));}
        assertFalse(Files.exists(directory.resolve(rows.id()+"-"+rows.token()+".csv")));
        var bytes=lease();try(var output=files.begin(bytes)){assertThrows(RoomExportFiles.ExportLimit.class,()->output.row(Map.of("ruleVersion","a".repeat(RoomExports.MAX_BYTES))));}
        assertFalse(Files.exists(directory.resolve(bytes.id()+"-"+bytes.token()+".csv")));
    }
    @Test void traversalAndSymlinkTargetsAreRejected() throws Exception {
        var files=new RoomExportFiles(directory.toRealPath().toString());var lease=lease();
        assertThrows(IllegalArgumentException.class,()->files.open(new RoomExports.Download("../outside",lease.token(),1)));
        Path target=directory.resolve("outside.txt");Files.writeString(target,"original");
        Path partial=directory.resolve(lease.id()+"-"+lease.token()+".part");Files.createSymbolicLink(partial,target);
        assertThrows(java.io.IOException.class,()->files.begin(lease));assertEquals("original",Files.readString(target));
        Path csv=directory.resolve(lease.id()+"-"+lease.token()+".csv");Files.createSymbolicLink(csv,target);
        assertThrows(java.io.IOException.class,()->files.open(new RoomExports.Download(lease.id(),lease.token(),8)));
        Path link=directory.resolve("linked-directory");Files.createSymbolicLink(link,directory);assertThrows(IllegalStateException.class,()->new RoomExportFiles(link.toString()));
    }
    @Test void cleanupOnlyRemovesOldGeneratedFilesAndNeverFollowsLinks() throws Exception {
        var files=new RoomExportFiles(directory.toRealPath().toString());var lease=lease();Instant now=Instant.now();
        Path old=directory.resolve(lease.id()+"-"+lease.token()+".part"),recent=directory.resolve(lease.id()+"-"+lease.token()+".csv"),unrelated=directory.resolve("notes.csv");
        Files.writeString(old,"old");Files.writeString(recent,"current");Files.writeString(unrelated,"keep");
        FileTime before=FileTime.from(now.minus(Duration.ofHours(26)));Files.setLastModifiedTime(old,before);Files.setLastModifiedTime(unrelated,before);
        var other=lease();Path link=directory.resolve(other.id()+"-"+other.token()+".csv");Files.createSymbolicLink(link,unrelated);
        files.clean(now);assertFalse(Files.exists(old));assertTrue(Files.exists(recent));assertEquals("keep",Files.readString(unrelated));assertTrue(Files.isSymbolicLink(link));
    }
}
