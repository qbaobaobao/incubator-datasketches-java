/*
 * Copyright 2017, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.hll;

import static com.yahoo.memory.UnsafeUtil.unsafe;
import static com.yahoo.sketches.hll.HllUtil.EMPTY;
import static com.yahoo.sketches.hll.HllUtil.LG_INIT_LIST_SIZE;
import static com.yahoo.sketches.hll.HllUtil.LG_INIT_SET_SIZE;
import static com.yahoo.sketches.hll.HllUtil.noWriteAccess;
import static com.yahoo.sketches.hll.PreambleUtil.EMPTY_FLAG_MASK;
import static com.yahoo.sketches.hll.PreambleUtil.HASH_SET_INT_ARR_START;
import static com.yahoo.sketches.hll.PreambleUtil.HASH_SET_PREINTS;
import static com.yahoo.sketches.hll.PreambleUtil.HLL_PREINTS;
import static com.yahoo.sketches.hll.PreambleUtil.LIST_INT_ARR_START;
import static com.yahoo.sketches.hll.PreambleUtil.LIST_PREINTS;
import static com.yahoo.sketches.hll.PreambleUtil.OUT_OF_ORDER_FLAG_MASK;
import static com.yahoo.sketches.hll.PreambleUtil.extractInt;
import static com.yahoo.sketches.hll.PreambleUtil.extractLgArr;
import static com.yahoo.sketches.hll.PreambleUtil.extractListCount;
import static com.yahoo.sketches.hll.PreambleUtil.extractOooFlag;
import static com.yahoo.sketches.hll.PreambleUtil.insertCurMin;
import static com.yahoo.sketches.hll.PreambleUtil.insertCurMode;
import static com.yahoo.sketches.hll.PreambleUtil.insertEmptyFlag;
import static com.yahoo.sketches.hll.PreambleUtil.insertFamilyId;
import static com.yahoo.sketches.hll.PreambleUtil.insertFlags;
import static com.yahoo.sketches.hll.PreambleUtil.insertInt;
import static com.yahoo.sketches.hll.PreambleUtil.insertKxQ0;
import static com.yahoo.sketches.hll.PreambleUtil.insertLgArr;
import static com.yahoo.sketches.hll.PreambleUtil.insertLgK;
import static com.yahoo.sketches.hll.PreambleUtil.insertListCount;
import static com.yahoo.sketches.hll.PreambleUtil.insertModes;
import static com.yahoo.sketches.hll.PreambleUtil.insertNumAtCurMin;
import static com.yahoo.sketches.hll.PreambleUtil.insertOooFlag;
import static com.yahoo.sketches.hll.PreambleUtil.insertPreInts;
import static com.yahoo.sketches.hll.PreambleUtil.insertSerVer;

import com.yahoo.memory.Memory;
import com.yahoo.memory.WritableMemory;
import com.yahoo.sketches.SketchesStateException;

/**
 * @author Lee Rhodes
 */
class DirectCouponList extends AbstractCoupons {
  WritableMemory wmem;
  Memory mem;
  Object memObj;
  long memAdd;

  //called from newInstance, writableWrap and DirectCouponHashSet
  DirectCouponList(final int lgConfigK, final TgtHllType tgtHllType, final CurMode curMode,
      final WritableMemory wmem) {
    super(lgConfigK, tgtHllType, curMode);
    this.wmem = wmem;
    mem = wmem;
    memObj = wmem.getArray();
    memAdd = wmem.getCumulativeOffset(0L);
  }

  //called from HllSketch.wrap and from DirectCouponHashSet constructor
  DirectCouponList(final int lgConfigK, final TgtHllType tgtHllType, final CurMode curMode,
      final Memory mem) {
    super(lgConfigK, tgtHllType, curMode);
    wmem = null;
    this.mem = mem;
    memObj = ((WritableMemory) mem).getArray();
    memAdd = mem.getCumulativeOffset(0L);
  }

  /**
   * Standard factory for new DirectCouponList.
   * This initializes the given WritableMemory.
   * @param lgConfigK the configured Lg K
   * @param tgtHllType the configured HLL target
   * @param dstMem the destination memory for the sketch.
   */
  static DirectCouponList newInstance(final int lgConfigK, final TgtHllType tgtHllType,
      final WritableMemory dstMem) {
    final Object memObj = dstMem.getArray();
    final long memAdd = dstMem.getCumulativeOffset(0L);

    insertPreInts(memObj, memAdd, LIST_PREINTS);
    insertSerVer(memObj, memAdd);
    insertFamilyId(memObj, memAdd);
    insertLgK(memObj, memAdd, lgConfigK);
    insertLgArr(memObj, memAdd, LG_INIT_LIST_SIZE);
    insertFlags(memObj, memAdd, EMPTY_FLAG_MASK);
    insertListCount(memObj, memAdd, 0);
    insertModes(memObj, memAdd, tgtHllType, CurMode.LIST);
    return new DirectCouponList(lgConfigK, tgtHllType, CurMode.LIST, dstMem);
  }

  @Override //returns on-heap List
  CouponList copy() {
    return CouponList.heapifyList(mem);
  }

  @Override //returns on-heap List
  CouponList copyAs(final TgtHllType tgtHllType) {
    final CouponList clist = CouponList.heapifyList(mem);
    return new CouponList(clist, tgtHllType);
  }

  @Override
  HllSketchImpl couponUpdate(final int coupon) {
    if (wmem == null) { noWriteAccess(); }
    final int len = 1 << getLgCouponArrInts();
    for (int i = 0; i < len; i++) { //search for empty slot
      final int couponAtIdx = extractInt(memObj, memAdd, LIST_INT_ARR_START + (i << 2));
      if (couponAtIdx == EMPTY) {
        insertInt(memObj, memAdd, LIST_INT_ARR_START + (i << 2), coupon);
        int couponCount = extractListCount(memObj, memAdd);
        insertListCount(memObj, memAdd, ++couponCount);
        insertEmptyFlag(memObj, memAdd, false);
        if (couponCount >= len) { //array full
          if (lgConfigK < 8) {
            return promoteListOrSetToHll(this);//oooFlag = false
          }
          return promoteListToSet(this); //oooFlag = true
        }
        return this;
      }
      //cell not empty
      if (couponAtIdx == coupon) { return this; } //duplicate
      //cell not empty & not a duplicate, continue
    } //end for
    throw new SketchesStateException("Invalid State: no empties & no duplicates");
  }

  @Override //get Coupons from internal Mem to dstMem
  //Called by CouponList.insertList()
  //Called by CouponList.insertSet()
  void getCouponsToMemoryInts(final WritableMemory dstWmem, final int lenInts) {
    mem.copyTo(LIST_INT_ARR_START, dstWmem, LIST_INT_ARR_START, lenInts << 2);
  }

  @Override
  int getCouponCount() {
    return extractListCount(memObj, memAdd);
  }

  @Override
  PairIterator getIterator() {
    final long offsetBytes = (curMode == CurMode.LIST) ? LIST_INT_ARR_START
        : HASH_SET_INT_ARR_START;
    return new DirectListSetIterator(mem, offsetBytes, 1 << getLgCouponArrInts());
  }

  @Override
  int getLgCouponArrInts() {
    return extractLgArr(memObj, memAdd);
  }

  @Override
  int getMemArrStart() {
    return LIST_INT_ARR_START;
  }

  @Override
  int getPreInts() {
    return LIST_PREINTS;
  }

  @Override
  boolean isMemory() {
    return true;
  }

  @Override
  boolean isOffHeap() {
    return mem.isDirect();
  }

  @Override
  boolean isOutOfOrderFlag() {
    return extractOooFlag(memObj, memAdd);
  }

  @Override
  void putOutOfOrderFlag(final boolean oooFlag) {
    assert wmem != null;
    insertOooFlag(memObj, memAdd, oooFlag);
  }

  //Called by DirectCouponList.couponUpdate()
  static final DirectCouponHashSet promoteListToSet(final DirectCouponList src) {
    final WritableMemory wmem = src.wmem;
    final Object memObj = wmem.getArray();
    final long memAdd = wmem.getCumulativeOffset(0L);

    //get the data from the current memory
    HllUtil.checkPreamble(wmem); //sanity check
    final int lgConfigK = src.lgConfigK;
    final TgtHllType tgtHllType = src.tgtHllType;
    final int srcOffset = LIST_INT_ARR_START;
    final int couponArrInts = 1 << src.getLgCouponArrInts();
    final int[] couponArr = new int[couponArrInts]; //buffer
    wmem.getIntArray(srcOffset, couponArr, 0, couponArrInts);

    //rewrite the memory image as a SET:
    insertPreInts(memObj, memAdd, HASH_SET_PREINTS);
    //SerVer, FamID, LgK  should be OK
    insertLgArr(memObj, memAdd, LG_INIT_SET_SIZE);
    insertFlags(memObj, memAdd, (byte) OUT_OF_ORDER_FLAG_MASK); //set oooFlag
    insertCurMin(memObj, memAdd, 0); //was list count
    insertCurMode(memObj,memAdd, CurMode.SET);
    //tgtHllType should already be set
    final int maxBytes = BaseHllSketch.getMaxUpdatableSerializationBytes(lgConfigK, tgtHllType);
    wmem.clear(LIST_INT_ARR_START, maxBytes - LIST_INT_ARR_START); //clears the hash set count

    //create the tgt
    final DirectCouponHashSet dchSet
        = new DirectCouponHashSet(src.lgConfigK, src.tgtHllType, src.wmem);

    //now reload the coupon data into the set
    for (int i = 0; i < couponArrInts; i++) {
      final int coupon = couponArr[i];
      if (coupon != EMPTY) {
        dchSet.couponUpdate(coupon);
      }
    }
    return dchSet;
  }

  static final DirectHllArray promoteListOrSetToHll(final DirectCouponList src) {
    final WritableMemory wmem = src.wmem;
    final Object memObj = wmem.getArray();
    final long memAdd = wmem.getCumulativeOffset(0L);

    //get the data from the current list or set memory
    final CurMode curMode = HllUtil.checkPreamble(wmem); //sanity check
    final int lgConfigK = src.lgConfigK;
    final TgtHllType tgtHllType = src.tgtHllType;
    final int srcOffset = (curMode == CurMode.LIST) ? LIST_INT_ARR_START : HASH_SET_INT_ARR_START;
    final double est = src.getEstimate();
    final int couponArrInts = 1 << src.getLgCouponArrInts();
    final int[] couponArr = new int[couponArrInts]; //buffer
    wmem.getIntArray(srcOffset, couponArr, 0, couponArrInts);

    //rewrite the memory image as an HLL
    insertPreInts(memObj, memAdd, HLL_PREINTS);
    //SerVer, FamID, LgK should be OK
    insertLgArr(memObj, memAdd, 0); //no Aux possible yet
    insertFlags(memObj, memAdd, 0); //clear all flags
    insertCurMin(memObj, memAdd, 0);
    insertCurMode(memObj,memAdd, CurMode.HLL);
    //tgtHllType should already be set
    //we update HipAccum at the end
    //clear KxQ0, KxQ1, NumAtCurMin, AuxCount, hllArray, auxArr
    final int maxBytes = BaseHllSketch.getMaxUpdatableSerializationBytes(lgConfigK, tgtHllType);
    wmem.clear(LIST_INT_ARR_START, maxBytes - LIST_INT_ARR_START);
    insertNumAtCurMin(memObj, memAdd, 1 << lgConfigK); //set numAtCurMin
    insertKxQ0(memObj, memAdd, 1 << lgConfigK);

    //choose the tgt
    final DirectHllArray dirHllArr;
    if (tgtHllType == TgtHllType.HLL_4) {
      dirHllArr = new DirectHll4Array(lgConfigK, wmem);
    } else if (tgtHllType == TgtHllType.HLL_6) {
      dirHllArr = new DirectHll6Array(lgConfigK, wmem);
    } else { //Hll_8
      dirHllArr = new DirectHll8Array(lgConfigK, wmem);
    }

    //now reload the coupon data into HLL
    for (int i = 0; i < couponArrInts; i++) {
      final int coupon = couponArr[i];
      if (coupon != EMPTY) {
        dirHllArr.couponUpdate(coupon);
      }
    }
    dirHllArr.putHipAccum(est);
    return dirHllArr;
  }

  //ITERATOR
  final class DirectListSetIterator extends IntMemoryPairIterator {

    DirectListSetIterator(final Memory mem, final long offsetBytes, final int lengthPairs) {
      super(mem, offsetBytes, lengthPairs, lgConfigK);
    }

    @Override
    int pair() {
      return unsafe.getInt(memObj, memAdd + offsetBytes + (index << 2));
    }
  }

}
