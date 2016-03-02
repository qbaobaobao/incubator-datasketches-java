/*
 * Copyright 2015, Yahoo! Inc.
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 */
package com.yahoo.sketches.theta;

import static com.yahoo.sketches.Family.idToFamily;
import static com.yahoo.sketches.Util.LS;
import static com.yahoo.sketches.Util.zeroPad;

import java.nio.ByteOrder;

import com.yahoo.sketches.Family;
import com.yahoo.sketches.memory.Memory;
import com.yahoo.sketches.memory.NativeMemory;

//@formatter:off
/**
 * This class defines the preamble data structure and provides basic utilities for some of the key
 * fields.
 * <p>The intent of the design of this class was to isolate the detailed knowledge of the bit and 
 * byte layout of the serialized form of the sketches derived from the Sketch class into one place. 
 * This allows the possibility of the introduction of different serialization 
 * schemes with minimal impact on the rest of the library.</p>
 *  
 * <p>
 * MAP: Low significance bytes of this <i>long</i> data structure are on the right. However, the
 * multi-byte integers (<i>int</i> and <i>long</i>) are stored in native byte order. The
 * <i>byte</i> values are treated as unsigned.</p>
 * 
 * <p>An empty CompactSketch only requires 8 bytes. An exact (non-estimating) compact 
 * sketch requres 16 bytes of preamble. UpdateSketches require 24 bytes of preamble. Union objects
 * require 32 bytes of preamble.</p>
 * 
 * <pre>
 * Long || Start Byte Adr:
 * Adr: 
 *      ||    7   |    6   |    5   |    4   |    3   |    2   |    1   |     0              |
 *  0   ||    Seed Hash    | Flags  |  LgArr |  lgNom | FamID  | SerVer | RF, Preamble_Longs |
 *  
 *      ||   15   |   14   |   13   |   12   |   11   |   10   |    9   |     8              |
 *  1   ||-----------------p-----------------|----------Retained Entries Count---------------|
 *  
 *      ||   23   |   22   |   21    |  20   |   19   |   18   |   17   |    16              |
 *  2   ||------------------------------THETA_LONG-------------------------------------------|
 *
 *      ||   31   |   30   |   29   |   28   |   27   |   26   |   25   |    24              |
 *  3   ||---------------------------Start of Long Array-------------------------------------|
 *  </pre>
 *  
 *  @author Lee Rhodes
 */
final class PreambleUtil {

  private PreambleUtil() {}
  
  // ###### DO NOT MESS WITH THIS FROM HERE ...
  // Preamble byte Addresses
  static final int PREAMBLE_LONGS_BYTE        = 0; //low 6 bits
  static final int LG_RESIZE_FACTOR_BYTE      = 0; //upper 2 bits. Not used by compact or direct.
  static final int SER_VER_BYTE               = 1;
  static final int FAMILY_BYTE                = 2; //SerVer1,2 was SKETCH_TYPE_BYTE
  static final int LG_NOM_LONGS_BYTE          = 3; //not used by compact
  static final int LG_ARR_LONGS_BYTE          = 4; //not used by compact
  static final int FLAGS_BYTE                 = 5; 
  static final int SEED_HASH_SHORT            = 6;  //byte 6,7
  static final int RETAINED_ENTRIES_INT       = 8;  //4 byte aligned
  static final int P_FLOAT                    = 12; //4 byte aligned, not used by compact
  static final int THETA_LONG                 = 16; //8-byte aligned
  static final int UNION_THETA_LONG           = 24; //8-byte aligned, only used by Union
  
  // flag bit masks
  static final int BIG_ENDIAN_FLAG_MASK = 1; //SerVer 1, 2, 3
  static final int READ_ONLY_FLAG_MASK  = 2; //Set but not read. Reserved. SerVer 1, 2, 3
  static final int EMPTY_FLAG_MASK      = 4; //SerVer 2, 3
  static final int COMPACT_FLAG_MASK    = 8; //SerVer 2 was NO_REBUILD_FLAG_MASK
  static final int ORDERED_FLAG_MASK    = 16;//SerVer 2 was UNORDERED_FLAG_MASK
  
  //Backward compatibility: SerVer1 preamble always 3 longs, SerVer2 preamble: 1, 2, 3 longs 
  //               SKETCH_TYPE_BYTE             2  //SerVer1, SerVer2
  //  V1, V2 types:  Alpha = 1, QuickSelect = 2, SetSketch = 3; V3 only: Buffered QS = 4
  static final int LG_RESIZE_RATIO_BYTE_V1    = 5; //used by SerVer 1
  static final int FLAGS_BYTE_V1              = 6; //used by SerVer 1
  
  //Other constants
  static final int SER_VER                    = 3;
  
  static final boolean NATIVE_ORDER_IS_BIG_ENDIAN  = 
      (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);
  
  static final double MAX_THETA_LONG_AS_DOUBLE = Long.MAX_VALUE;

  static final int getReqMemBytesFull(int lgNomLongs, int preambleLongs) {
    return (16 << lgNomLongs) + (preambleLongs << 3);
  }

  static final int getMemBytes(int lgArrLongs, int preambleLongs) {
    return (8 << lgArrLongs) + (preambleLongs << 3);
  }

  // STRINGS
  /**
   * Returns a human readable string summary of the internal state of the given byte array. Used
   * primarily in testing.
   * 
   * @param byteArr the given byte array.
   * @return the summary string.
   */
  public static String toString(byte[] byteArr) {
    Memory mem = new NativeMemory(byteArr);
    return toString(mem);
  }

  /**
   * Returns a human readable string summary of the internal state of the given Memory. 
   * Used primarily in testing.
   * 
   * @param mem the given Memory
   * @return the summary string.
   */
  public static String toString(Memory mem) {
    return memoryToString(mem);
  }

  private static String memoryToString(Memory mem) {
    int preLongs = (mem.getByte(PREAMBLE_LONGS_BYTE)) & 0X3F;
    int serVer = mem.getByte(SER_VER_BYTE);
    int familyID = mem.getByte(FAMILY_BYTE);
    Family family = idToFamily(familyID);
    int lgNomLongs = mem.getByte(LG_NOM_LONGS_BYTE);
    int lgArrLongs = mem.getByte(LG_ARR_LONGS_BYTE);
    int flags = mem.getByte(FLAGS_BYTE);
    String flagsStr = zeroPad(Integer.toBinaryString(flags), 8) + ", " + (flags);
    boolean bigEndian = (flags & BIG_ENDIAN_FLAG_MASK) > 0;
    String nativeOrder = ByteOrder.nativeOrder().toString();
    boolean compact = (flags & COMPACT_FLAG_MASK) > 0;
    boolean ordered = (flags & ORDERED_FLAG_MASK) > 0;
    boolean readOnly = (flags & READ_ONLY_FLAG_MASK) > 0;
    boolean empty = (flags & EMPTY_FLAG_MASK) > 0;
    
    short seedHash = mem.getShort(SEED_HASH_SHORT);
    int curCount = 0;
    float p = (float)1.0;
    long thetaLong = (long)(p * MAX_THETA_LONG_AS_DOUBLE);
    long thetaULong = thetaLong;
    if (preLongs == 2) {
      curCount = mem.getInt(RETAINED_ENTRIES_INT);
      p = mem.getFloat(P_FLOAT);
      thetaLong = (long)(p * MAX_THETA_LONG_AS_DOUBLE);
      thetaULong = thetaLong;
    } 
    else if (preLongs == 3){
      curCount = mem.getInt(RETAINED_ENTRIES_INT);
      p = mem.getFloat(P_FLOAT);
      thetaLong = mem.getLong(THETA_LONG);
      thetaULong = thetaLong;
    } 
    else if (preLongs == 4) {
      curCount = mem.getInt(RETAINED_ENTRIES_INT);
      p = mem.getFloat(P_FLOAT);
      thetaLong = mem.getLong(THETA_LONG);
      thetaULong = mem.getLong(UNION_THETA_LONG);
    } //else: the same as preLongs == 1
    double thetaDbl = thetaLong / MAX_THETA_LONG_AS_DOUBLE;
    String thetaHex = zeroPad(Long.toHexString(thetaLong), 16);
    double thetaUDbl = thetaULong / MAX_THETA_LONG_AS_DOUBLE;
    String thetaUHex = zeroPad(Long.toHexString(thetaULong), 16);
    
    StringBuilder sb = new StringBuilder();
    sb.append(LS)
      .append("### SKETCH PREAMBLE SUMMARY:").append(LS)
      .append("Byte  0: Preamble Longs       : ").append(preLongs).append(LS)
      .append("Byte  1: Serialization Version: ").append(serVer).append(LS)
      .append("Byte  2: Family               : ").append(family.toString()).append(LS)
      .append("Byte  3: LgNomLongs           : ").append(lgNomLongs).append(LS)
      .append("Byte  4: LgArrLongs           : ").append(lgArrLongs).append(LS)
      .append("Byte  5: Flags Field          : ").append(flagsStr).append(LS)
      .append("  BIG_ENDIAN_STORAGE          : ").append(bigEndian).append(LS)
      .append("  (Native Byte Order)         : ").append(nativeOrder).append(LS)
      .append("  READ_ONLY                   : ").append(readOnly).append(LS)
      .append("  EMPTY                       : ").append(empty).append(LS)
      .append("  COMPACT                     : ").append(compact).append(LS)
      .append("  ORDERED                     : ").append(ordered).append(LS)
      .append("Bytes 6-7  : Seed Hash        : ").append(Integer.toHexString(seedHash)).append(LS);
    if (preLongs == 1) {
      sb.append(" --ABSENT, ASSUMED:").append(LS);
      sb.append("Bytes 8-11 : CurrentCount     : ").append(curCount).append(LS)
        .append("Bytes 12-15: P                : ").append(p).append(LS);
      sb.append("Bytes 16-23: Theta (double)   : ").append(thetaDbl).append(LS)
        .append("             Theta (long)     : ").append(thetaLong).append(LS)
        .append("             Theta (long,hex) : ").append(thetaHex).append(LS);
      if (family == Family.UNION) {
      sb.append("Bytes 25-31: ThetaU (double)  : ").append(thetaUDbl).append(LS)
        .append("             ThetaU (long)    : ").append(thetaULong).append(LS)
        .append("             ThetaU (long,hex): ").append(thetaUHex).append(LS);
      }
    }
    if (preLongs == 2) {
      sb.append("Bytes 8-11 : CurrentCount     : ").append(curCount).append(LS)
        .append("Bytes 12-15: P                : ").append(p).append(LS);
      sb.append(" --ABSENT, ASSUMED:").append(LS);
      sb.append("Bytes 16-23: Theta (double)   : ").append(thetaDbl).append(LS)
        .append("             Theta (long)     : ").append(thetaLong).append(LS)
        .append("             Theta (long,hex) : ").append(thetaHex).append(LS);
      if (family == Family.UNION) {
      sb.append("Bytes 25-31: ThetaU (double)  : ").append(thetaUDbl).append(LS)
        .append("             ThetaU (long)    : ").append(thetaULong).append(LS)
        .append("             ThetaU (long,hex): ").append(thetaUHex).append(LS);
      }
    }
    if (preLongs == 3) {
      sb.append("Bytes 8-11 : CurrentCount     : ").append(curCount).append(LS)
        .append("Bytes 12-15: P                : ").append(p).append(LS);
      sb.append("Bytes 16-23: Theta (double)   : ").append(thetaDbl).append(LS)
        .append("             Theta (long)     : ").append(thetaLong).append(LS)
        .append("             Theta (long,hex) : ").append(thetaHex).append(LS);
      if (family == Family.UNION) {
      sb.append(" --ABSENT, ASSUMED:").append(LS);
      sb.append("Bytes 25-31: ThetaU (double)  : ").append(thetaUDbl).append(LS)
        .append("             ThetaU (long)    : ").append(thetaULong).append(LS)
        .append("             ThetaU (long,hex): ").append(thetaUHex).append(LS);
      }
    }
    if (preLongs == 4) {
      sb.append("Bytes 8-11 : CurrentCount     : ").append(curCount).append(LS)
        .append("Bytes 12-15: P                : ").append(p).append(LS);
      sb.append("Bytes 16-23: Theta (double)   : ").append(thetaDbl).append(LS)
        .append("             Theta (long)     : ").append(thetaLong).append(LS)
        .append("             Theta (long,hex) : ").append(thetaHex).append(LS);
      sb.append("Bytes 25-31: ThetaU (double)  : ").append(thetaUDbl).append(LS)
        .append("             ThetaU (long)    : ").append(thetaULong).append(LS)
        .append("             ThetaU (long,hex): ").append(thetaUHex).append(LS);
    }
    sb.append(  "Preamble Bytes                : ").append(preLongs * 8).append(LS);
    sb.append(  "Data Bytes                    : ").append(curCount * 8).append(LS);
    sb.append(  "TOTAL Sketch Bytes            : ").append(mem.getCapacity()).append(LS)
      .append("### END SKETCH PREAMBLE SUMMARY").append(LS);
    return sb.toString();
  }
  
//@formatter:on
  
  //Extract from long and insert into long methods
  
  static int extractPreLongs(final long long0) {
    long mask = 0X3FL;
    return (int) (long0 & mask);
  }
  
  static int extractResizeFactor(final long long0) {
    int shift = 6;
    long mask = 0X3L;
    return (int) ((long0 >>> shift) & mask);
  }
  
  static int extractSerVer(final long long0) {
    int shift = SER_VER_BYTE << 3;
    long mask = 0XFFL;
    return (int) ((long0 >>> shift) & mask);
  }
  
  static int extractFamilyID(final long long0) {
    int shift = FAMILY_BYTE << 3;
    long mask = 0XFFL;
    return (int) ((long0 >>> shift) & mask);
  }
  
  static int extractLgNomLongs(final long long0) {
    int shift = LG_NOM_LONGS_BYTE << 3;
    long mask = 0XFFL;
    return (int) ((long0 >>> shift) & mask);
  }
  
  static int extractLgArrLongs(final long long0) {
    int shift = LG_ARR_LONGS_BYTE << 3;
    long mask = 0XFFL;
    return (int) ((long0 >>> shift) & mask);
  }
  
  static int extractFlags(final long long0) {
    int shift = FLAGS_BYTE << 3;
    long mask = 0XFFL;
    return (int) ((long0 >>> shift) & mask);
  }
  
  static int extractFlagsV1(final long long0) {
    int shift = FLAGS_BYTE_V1 << 3;
    long mask = 0XFFL;
    return (int) ((long0 >>> shift) & mask);
  }
  
  static int extractSeedHash(final long long0) {
    int shift = SEED_HASH_SHORT << 3;
    long mask = 0XFFFFL;
    return (int) ((long0 >>> shift) & mask);
  }
  
  static int extractCurCount(final long long1) {
    long mask = 0XFFFFFFFFL;
    return (int) (long1 & mask);
  }
  
  static float extractP(final long long1) {
    int shift = 32;
    return Float.intBitsToFloat((int)(long1 >>> shift));
  }
  
  static long insertPreLongs(final int preLongs, final long long0) {
    long mask = 0X3FL;
    return (preLongs & mask) | (~mask & long0);
  }
  
  static long insertResizeFactor(final int rf, final long long0) {
    int shift = 6;
    long mask = 3L;
    return ((rf & mask) << shift) | (~(mask << shift) & long0);
  }
  
  static long insertSerVer(final int serVer, final long long0) {
    int shift = SER_VER_BYTE << 3;
    long mask = 0XFFL;
    return ((serVer & mask) << shift) | (~(mask << shift) & long0);
  }
  
  static long insertFamilyID(final int familyID, final long long0) {
    int shift = FAMILY_BYTE << 3;
    long mask = 0XFFL;
    return ((familyID & mask) << shift) | (~(mask << shift) & long0);
  }
  
  static long insertLgNomLongs(final int lgNomLongs, final long long0) {
    int shift = LG_NOM_LONGS_BYTE << 3;
    long mask = 0XFFL;
    return ((lgNomLongs & mask) << shift) | (~(mask << shift) & long0);
  }
  
  static long insertLgArrLongs(final int lgArrLongs, final long long0) {
    int shift = LG_ARR_LONGS_BYTE << 3;
    long mask = 0XFFL;
    return ((lgArrLongs & mask) << shift) | (~(mask << shift) & long0);
  }
  
  static long insertFlags(final int flags, final long long0) {
    int shift = FLAGS_BYTE << 3;
    long mask = 0XFFL;
    return ((flags & mask) << shift) | (~(mask << shift) & long0);
  }
  
  static long insertSeedHash(final int seedHash, final long long0) {
    int shift = SEED_HASH_SHORT << 3;
    long mask = 0XFFFFL;
    return ((seedHash & mask) << shift) | (~(mask << shift) & long0);
  }
  
  static long insertCurCount(final int curCount, final long long1) {
    long mask = 0XFFFFFFFFL;
    return (curCount & mask) | (~mask & long1);
  }
  
  static long insertP(final float p, final long long1) {
    int shift = 32;
    long mask = 0XFFFFFFFFL;
    return ((Float.floatToRawIntBits(p) & mask) << shift) | (~(mask << shift) & long1);
  }
  
}
