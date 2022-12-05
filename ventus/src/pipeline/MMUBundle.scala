/***************************************************************************************
  * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
  * Copyright (c) 2020-2021 Peng Cheng Laboratory
  *
  * XiangShan is licensed under Mulan PSL v2.
  * You can use this software according to the terms and conditions of the Mulan PSL v2.
  * You may obtain a copy of Mulan PSL v2 at:
  *          http://license.coscl.org.cn/MulanPSL2
  *
  * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
  * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
  * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
  *
  * See the Mulan PSL v2 for more details.
  ***************************************************************************************/

package pipeline
import chisel3._
import chisel3.util._



abstract class TlbBundle extends Bundle with HasTlbConst
abstract class TlbModule extends Module with HasTlbConst

class VaBundle extends TlbBundle {
  val vpn  = UInt(vpnLen.W)
  val off  = UInt(offLen.W)
}

case class MMPMAConfig
(
  address: BigInt,
  mask: BigInt,
  lgMaxSize: Int,
  sameCycle: Boolean,
  num: Int
)
trait HasPMParameters extends HasVTParameter{
  val PMPAddrBits = PAddrBits
  val PMXLEN = vlen // to check
  val NumPMP = 16
  val NumPMA = 16
  val mmpma: MMPMAConfig = MMPMAConfig(
    address = 0x38021000,
    mask = 0xfff,
    lgMaxSize = 3,
    sameCycle = true,
    num = 2
  )
  val PlatformGrain = log2Ceil(4*1024) // 4KB, a normal page

}
trait PMPConst extends HasPMParameters {
  val PMPOffBits = 2 // minimal 4bytes
  val CoarserGrain: Boolean = PlatformGrain > PMPOffBits
}
abstract class PMPBundle extends Bundle with PMPConst
class PMPConfig extends PMPBundle {
  val l = Bool()
  val c = Bool() // res(1), unuse in pmp
  val atomic = Bool() // res(0), unuse in pmp
  val a = UInt(2.W)
  val x = Bool()
  val w = Bool()
  val r = Bool()

  def res: UInt = Cat(c, atomic) // in pmp, unused
  def off = a === 0.U
  def tor = a === 1.U
  def na4 = { if (CoarserGrain) false.B else a === 2.U }
  def napot = { if (CoarserGrain) a(1).asBool else a === 3.U }
  def off_tor = !a(1)
  def na4_napot = a(1)

  def locked = l
  def addr_locked: Bool = locked
  def addr_locked(next: PMPConfig): Bool = locked || (next.locked && next.tor)
}

class PtePermBundle extends TlbBundle {
  val d = Bool()
  val a = Bool()
  val g = Bool()
  val u = Bool()
  val x = Bool()
  val w = Bool()
  val r = Bool()

  override def toPrintable: Printable = {
    p"d:${d} a:${a} g:${g} u:${u} x:${x} w:${w} r:${r}"// +
    //(if(hasV) (p"v:${v}") else p"")
  }
}

class TlbPMBundle extends TlbBundle {
  val r = Bool()
  val w = Bool()
  val x = Bool()
  val c = Bool()
  val atomic = Bool()

  def assign_ap(pm: PMPConfig) = {
    r := pm.r
    w := pm.w
    x := pm.x
    c := pm.c
    atomic := pm.atomic
  }
}

class TlbPermBundle extends TlbBundle {
  val pf = Bool() // NOTE: if this is true, just raise pf
  val af = Bool() // NOTE: if this is true, just raise af
  // pagetable perm (software defined)
  val d = Bool()
  val a = Bool()
  val g = Bool()
  val u = Bool()
  val x = Bool()
  val w = Bool()
  val r = Bool()

  val pm = new TlbPMBundle

  def apply(item: PtwResp, pm: PMPConfig) = {
    val ptePerm = item.entry.perm.get.asTypeOf(new PtePermBundle().cloneType)
    this.pf := item.pf
    this.af := item.af
    this.d := ptePerm.d
    this.a := ptePerm.a
    this.g := ptePerm.g
    this.u := ptePerm.u
    this.x := ptePerm.x
    this.w := ptePerm.w
    this.r := ptePerm.r

    this.pm.assign_ap(pm)
    this
  }
  override def toPrintable: Printable = {
    p"pf:${pf} af:${af} d:${d} a:${a} g:${g} u:${u} x:${x} w:${w} r:${r} " +
      p"pm:${pm}"
  }
}

// multi-read && single-write
// input is data, output is hot-code(not one-hot)
class CAMTemplate[T <: Data](val gen: T, val set: Int, val readWidth: Int) extends TlbModule {
  val io = IO(new Bundle {
    val r = new Bundle {
      val req = Input(Vec(readWidth, gen))
      val resp = Output(Vec(readWidth, Vec(set, Bool())))
    }
    val w = Input(new Bundle {
      val valid = Bool()
      val bits = new Bundle {
        val index = UInt(log2Up(set).W)
        val data = gen
      }
    })
  })

  val wordType = UInt(gen.getWidth.W)
  val array = Reg(Vec(set, wordType))

  io.r.resp.zipWithIndex.map{ case (a,i) =>
    a := array.map(io.r.req(i).asUInt === _)
  }

  when (io.w.valid) {
    array(io.w.bits.index) := io.w.bits.data.asUInt
  }
}

class TlbEntry(pageNormal: Boolean, pageSuper: Boolean) extends TlbBundle {
  require(pageNormal || pageSuper)

  val tag = if (!pageNormal) UInt((vpnLen - vpnnLen).W)
  else UInt(vpnLen.W)
  val asid = UInt(asidLen.W)
  val level = if (!pageNormal) Some(UInt(1.W))
  else if (!pageSuper) None
  else Some(UInt(2.W))
  val ppn = if (!pageNormal) UInt((ppnLen - vpnnLen).W)
  else UInt(ppnLen.W)
  val perm = new TlbPermBundle

  /** level usage:
    *  !PageSuper: page is only normal, level is None, match all the tag
    *  !PageNormal: page is only super, level is a Bool(), match high 9*2 parts
    *  bits0  0: need mid 9bits
    *         1: no need mid 9bits
    *  PageSuper && PageNormal: page hold all the three type,
    *  bits0  0: need low 9bits
    *  bits1  0: need mid 9bits
    */

  def hit(vpn: UInt, asid: UInt, nSets: Int = 1, ignoreAsid: Boolean = false): Bool = {
    val asid_hit = if (ignoreAsid) true.B else (this.asid === asid)

    // NOTE: for timing, dont care low set index bits at hit check
    //       do not need store the low bits actually
    if (!pageSuper) asid_hit && drop_set_equal(vpn, tag, nSets)
    else if (!pageNormal) {
      val tag_match_hi = tag(vpnnLen*2-1, vpnnLen) === vpn(vpnnLen*3-1, vpnnLen*2)
      val tag_match_mi = tag(vpnnLen-1, 0) === vpn(vpnnLen*2-1, vpnnLen)
      val tag_match = tag_match_hi && (level.get.asBool() || tag_match_mi)
      asid_hit && tag_match
    }
    else {
      val tmp_level = level.get
      val tag_match_hi = tag(vpnnLen*3-1, vpnnLen*2) === vpn(vpnnLen*3-1, vpnnLen*2)
      val tag_match_mi = tag(vpnnLen*2-1, vpnnLen) === vpn(vpnnLen*2-1, vpnnLen)
      val tag_match_lo = tag(vpnnLen-1, 0) === vpn(vpnnLen-1, 0) // if pageNormal is false, this will always be false
      val tag_match = tag_match_hi && (tmp_level(1) || tag_match_mi) && (tmp_level(0) || tag_match_lo)
      asid_hit && tag_match
    }
  }

  def apply(item: PtwResp, asid: UInt, pm: PMPConfig): TlbEntry = {
    this.tag := {if (pageNormal) item.entry.tag else item.entry.tag(vpnLen-1, vpnnLen)}
    this.asid := asid
    val inner_level = item.entry.level.getOrElse(0.U)
    this.level.map(_ := { if (pageNormal && pageSuper) MuxLookup(inner_level, 0.U, Seq(
      0.U -> 3.U,
      1.U -> 1.U,
      2.U -> 0.U ))
    else if (pageSuper) ~inner_level(0)
    else 0.U })
    this.ppn := { if (!pageNormal) item.entry.ppn(ppnLen-1, vpnnLen)
    else item.entry.ppn }
    this.perm.apply(item, pm)
    this
  }

  // 4KB is normal entry, 2MB/1GB is considered as super entry
  def is_normalentry(): Bool = {
    if (!pageSuper) { true.B }
    else if (!pageNormal) { false.B }
    else { level.get === 0.U }
  }

  def genPPN(saveLevel: Boolean = false, valid: Bool = false.B)(vpn: UInt) : UInt = {
    val inner_level = level.getOrElse(0.U)
    val ppn_res = if (!pageSuper) ppn
    else if (!pageNormal) Cat(ppn(ppnLen-vpnnLen-1, vpnnLen),
      Mux(inner_level(0), vpn(vpnnLen*2-1, vpnnLen), ppn(vpnnLen-1,0)),
      vpn(vpnnLen-1, 0))
    else Cat(ppn(ppnLen-1, vpnnLen*2),
      Mux(inner_level(1), vpn(vpnnLen*2-1, vpnnLen), ppn(vpnnLen*2-1, vpnnLen)),
      Mux(inner_level(0), vpn(vpnnLen-1, 0), ppn(vpnnLen-1, 0)))

    if (saveLevel) Cat(ppn(ppn.getWidth-1, vpnnLen*2), RegEnable(ppn_res(vpnnLen*2-1, 0), valid))
    else ppn_res
  }

  override def toPrintable: Printable = {
    val inner_level = level.getOrElse(2.U)
    p"asid: ${asid} level:${inner_level} vpn:${Hexadecimal(tag)} ppn:${Hexadecimal(ppn)} perm:${perm}"
  }

}

object TlbCmd {
  def read  = "b00".U
  def write = "b01".U
  def exec  = "b10".U

  def atom_read  = "b100".U // lr
  def atom_write = "b101".U // sc / amo

  def apply() = UInt(3.W)
  def isRead(a: UInt) = a(1,0)===read
  def isWrite(a: UInt) = a(1,0)===write
  def isExec(a: UInt) = a(1,0)===exec

  def isAtom(a: UInt) = a(2)
  def isAmo(a: UInt) = a===atom_write // NOTE: sc mixed
}

class TlbStorageIO(nSets: Int, nWays: Int, ports: Int, nDups: Int = 1) extends MMUIOBaseBundle {
  val r = new Bundle {
    val req = Vec(ports, Flipped(DecoupledIO(new Bundle {
      val vpn = Output(UInt(vpnLen.W))
    })))
    val resp = Vec(ports, ValidIO(new Bundle{
      val hit = Output(Bool())
      val ppn = Vec(nDups, Output(UInt(ppnLen.W)))
      val perm = Vec(nDups, Output(new TlbPermBundle()))
    }))
  }
  val w = Flipped(ValidIO(new Bundle {
    val wayIdx = Output(UInt(log2Up(nWays).W))
    val data = Output(new PtwResp)
    val data_replenish = Output(new PMPConfig)
  }))
  val victim = new Bundle {
    val out = ValidIO(Output(new Bundle {
      val entry = new TlbEntry(pageNormal = true, pageSuper = false)
    }))
    val in = Flipped(ValidIO(Output(new Bundle {
      val entry = new TlbEntry(pageNormal = true, pageSuper = false)
    })))
  }
  val access = Vec(ports, new ReplaceAccessBundle(nSets, nWays))

  def r_req_apply(valid: Bool, vpn: UInt, i: Int): Unit = {
    this.r.req(i).valid := valid
    this.r.req(i).bits.vpn := vpn
  }

  def r_resp_apply(i: Int) = {
    (this.r.resp(i).bits.hit, this.r.resp(i).bits.ppn, this.r.resp(i).bits.perm)
  }

  def w_apply(valid: Bool, wayIdx: UInt, data: PtwResp, data_replenish: PMPConfig): Unit = {
    this.w.valid := valid
    this.w.bits.wayIdx := wayIdx
    this.w.bits.data := data
    this.w.bits.data_replenish := data_replenish
  }

}

class TlbStorageWrapperIO(ports: Int, q: TLBParameters, nDups: Int = 1) extends MMUIOBaseBundle {
  val r = new Bundle {
    val req = Vec(ports, Flipped(DecoupledIO(new Bundle {
      val vpn = Output(UInt(vpnLen.W))
    })))
    val resp = Vec(ports, ValidIO(new Bundle{
      val hit = Output(Bool())
      val ppn = Vec(nDups, Output(UInt(ppnLen.W)))
      val perm = Vec(nDups, Output(new TlbPermBundle()))
      // below are dirty code for timing optimization
      val super_hit = Output(Bool())
      val super_ppn = Output(UInt(ppnLen.W))
      val spm = Output(new TlbPMBundle)
    }))
  }
  val w = Flipped(ValidIO(new Bundle {
    val data = Output(new PtwResp)
    val data_replenish = Output(new PMPConfig)
  }))
  val replace = if (q.outReplace) Flipped(new TlbReplaceIO(ports, q)) else null

  def r_req_apply(valid: Bool, vpn: UInt, i: Int): Unit = {
    this.r.req(i).valid := valid
    this.r.req(i).bits.vpn := vpn
  }

  def r_resp_apply(i: Int) = {
    (this.r.resp(i).bits.hit, this.r.resp(i).bits.ppn, this.r.resp(i).bits.perm,
      this.r.resp(i).bits.super_hit, this.r.resp(i).bits.super_ppn, this.r.resp(i).bits.spm)
  }

  def w_apply(valid: Bool, data: PtwResp, data_replenish: PMPConfig): Unit = {
    this.w.valid := valid
    this.w.bits.data := data
    this.w.bits.data_replenish := data_replenish
  }
}

class ReplaceAccessBundle(nSets: Int, nWays: Int)extends TlbBundle {
  val sets = Output(UInt(log2Up(nSets).W))
  val touch_ways = ValidIO(Output(UInt(log2Up(nWays).W)))

}

class ReplaceIO(Width: Int, nSets: Int, nWays: Int)extends TlbBundle {
  val access = Vec(Width, Flipped(new ReplaceAccessBundle(nSets, nWays)))

  val refillIdx = Output(UInt(log2Up(nWays).W))
  val chosen_set = Flipped(Output(UInt(log2Up(nSets).W)))

  def apply_sep(in: Seq[ReplaceIO], vpn: UInt): Unit = {
    for ((ac_rep, ac_tlb) <- access.zip(in.map(a => a.access.map(b => b)).flatten)) {
      ac_rep := ac_tlb
    }
    this.chosen_set := get_set_idx(vpn, nSets)
    in.map(a => a.refillIdx := this.refillIdx)
  }
}

class TlbReplaceIO(Width: Int, q: TLBParameters)extends
  TlbBundle {
  val normalPage = new ReplaceIO(Width, q.normalNSets, q.normalNWays)
  val superPage = new ReplaceIO(Width, q.superNSets, q.superNWays)

  def apply_sep(in: Seq[TlbReplaceIO], vpn: UInt) = {
    this.normalPage.apply_sep(in.map(_.normalPage), vpn)
    this.superPage.apply_sep(in.map(_.superPage), vpn)
  }

}

class TlbReq extends TlbBundle {
  val vaddr = Output(UInt(VAddrBits.W))
  val cmd = Output(TlbCmd())
  val size = Output(UInt(log2Ceil(log2Ceil(XLEN/8)+1).W))
  val kill = Output(Bool()) // Use for blocked tlb that need sync with other module like icache
  /*val debug = new Bundle {
    val pc = Output(UInt(XLEN.W))
    val robIdx = Output(new RobPtr)
    val isFirstIssue = Output(Bool())
  }

  // Maybe Block req needs a kill: for itlb, itlb and icache may not sync, itlb should wait icache to go ahead
  override def toPrintable: Printable = {
    p"vaddr:0x${Hexadecimal(vaddr)} cmd:${cmd} kill:${kill} pc:0x${Hexadecimal(debug.pc)} robIdx:${debug.robIdx}"
  }*/
}

class TlbExceptionBundle extends TlbBundle {
  val ld = Output(Bool())
  val st = Output(Bool())
  val instr = Output(Bool())
}

class TlbResp(nDups: Int = 1) extends TlbBundle {
  val paddr = Vec(nDups, Output(UInt(PAddrBits.W)))
  val miss = Output(Bool())
  val fast_miss = Output(Bool()) // without sram part for timing optimization
  val excp = Vec(nDups, new Bundle {
    val pf = new TlbExceptionBundle()
    val af = new TlbExceptionBundle()
  })
  val static_pm = Output(Valid(Bool())) // valid for static, bits for mmio result from normal entries
  val ptwBack = Output(Bool()) // when ptw back, wake up replay rs's state

  override def toPrintable: Printable = {
    p"paddr:0x${Hexadecimal(paddr(0))} miss:${miss} excp.pf: ld:${excp(0).pf.ld} st:${excp(0).pf.st} instr:${excp(0).pf.instr} ptwBack:${ptwBack}"
  }
}

class TlbRequestIO(nRespDups: Int = 1)extends TlbBundle {
  val req = DecoupledIO(new TlbReq)
  val req_kill = Output(Bool())
  val resp = Flipped(DecoupledIO(new TlbResp(nRespDups)))
}

class TlbPtwIO(Width: Int = 1) extends TlbBundle {
  val req = Vec(Width, DecoupledIO(new PtwReq))
  val resp = Flipped(DecoupledIO(new PtwResp))


  override def toPrintable: Printable = {
    p"req(0):${req(0).valid} ${req(0).ready} ${req(0).bits} | resp:${resp.valid} ${resp.ready} ${resp.bits}"
  }
}


class SatpStruct extends Bundle {
  val mode = UInt(4.W)
  val asid = UInt(16.W)
  val ppn  = UInt(44.W)
}
object DataChanged {
  def apply(data: UInt): UInt = {
    data =/= RegNext(data)
  }
}
class TlbSatpBundle extends SatpStruct with HasTlbConst {
  val changed = Bool()

  def apply(satp_value: UInt): Unit = {
    require(satp_value.getWidth == XLEN)
    val sa = satp_value.asTypeOf(new SatpStruct)
    mode := sa.mode
    asid := sa.asid
    ppn := Cat(0.U(44-PAddrBits), sa.ppn(PAddrBits-1, 0)).asUInt()
    changed := DataChanged(sa.asid) // when ppn is changed, software need do the flush
  }
}
class TlbCsrBundle extends Bundle {
  val satp = new TlbSatpBundle()
  val priv = new Bundle {
    val mxr = Bool()
    val sum = Bool()
    val imode = UInt(2.W)
    val dmode = UInt(2.W)
  }

  override def toPrintable: Printable = {
    p"Satp mode:0x${Hexadecimal(satp.mode)} asid:0x${Hexadecimal(satp.asid)} ppn:0x${Hexadecimal(satp.ppn)} " +
      p"Priv mxr:${priv.mxr} sum:${priv.sum} imode:${priv.imode} dmode:${priv.dmode}"
  }
}
class MMUIOBaseBundle extends TlbBundle {
//  val sfence = Input(new SfenceBundle)
  val csr = Input(new TlbCsrBundle)

  def base_connect(/*sfence: SfenceBundle, */csr: TlbCsrBundle): Unit = {
//    this.sfence <> sfence
    this.csr <> csr
  }

  // overwrite satp. write satp will cause flushpipe but csr.priv won't
  // satp will be dealyed several cycles from writing, but csr.priv won't
  // so inside mmu, these two signals should be divided
  def base_connect(/*sfence: SfenceBundle,*/ csr: TlbCsrBundle, satp: TlbSatpBundle) = {
//    this.sfence <> sfence
    this.csr <> csr
    this.csr.satp := satp
  }
}

class TlbIO(Width: Int, nRespDups: Int = 1, q: TLBParameters) extends
  MMUIOBaseBundle {
  val requestor = Vec(Width, Flipped(new TlbRequestIO(nRespDups)))
  val flushPipe = Vec(Width, Input(Bool()))
  val ptw = new TlbPtwIO(Width)
  val ptw_replenish = Input(new PMPConfig())
  val replace = if (q.outReplace) Flipped(new TlbReplaceIO(Width, q)) else null
  //val pmp = Vec(Width, ValidIO(new PMPReqBundle()))

}

class VectorTlbPtwIO(Width: Int) extends TlbBundle {
  val req = Vec(Width, DecoupledIO(new PtwReq))
  val resp = Flipped(DecoupledIO(new Bundle {
    val data = new PtwResp
    val vector = Output(Vec(Width, Bool()))
  }))

  def connect(normal: TlbPtwIO): Unit = {
    req <> normal.req
    resp.ready := normal.resp.ready
    normal.resp.bits := resp.bits.data
    normal.resp.valid := resp.valid
  }
}

/****************************  L2TLB  *************************************/
abstract class PtwBundle extends Bundle with HasPtwConst
abstract class PtwModule extends Module with HasVTParameter with HasPtwConst

class PteBundle extends PtwBundle{
  val reserved  = UInt(pteResLen.W)
  val ppn  = UInt(ppnLen.W)
  val rsw  = UInt(2.W)
  val perm = new Bundle {
    val d    = Bool()
    val a    = Bool()
    val g    = Bool()
    val u    = Bool()
    val x    = Bool()
    val w    = Bool()
    val r    = Bool()
    val v    = Bool()
  }

  def unaligned(level: UInt) = {
    isLeaf() && !(level === 2.U ||
      level === 1.U && ppn(vpnnLen-1,   0) === 0.U ||
      level === 0.U && ppn(vpnnLen*2-1, 0) === 0.U)
  }

  def isPf(level: UInt) = {
    !perm.v || (!perm.r && perm.w) || unaligned(level)
  }

  def isLeaf() = {
    perm.r || perm.x || perm.w
  }

  def getPerm() = {
    val pm = Wire(new PtePermBundle)
    pm.d := perm.d
    pm.a := perm.a
    pm.g := perm.g
    pm.u := perm.u
    pm.x := perm.x
    pm.w := perm.w
    pm.r := perm.r
    pm
  }

  override def toPrintable: Printable = {
    p"ppn:0x${Hexadecimal(ppn)} perm:b${Binary(perm.asUInt)}"
  }
}

class PtwEntry(tagLen: Int, hasPerm: Boolean = false, hasLevel: Boolean = false) extends PtwBundle {
  val tag = UInt(tagLen.W)
  val asid = UInt(asidLen.W)
  val ppn = UInt(ppnLen.W)
  val perm = if (hasPerm) Some(new PtePermBundle) else None
  val level = if (hasLevel) Some(UInt(log2Up(Level).W)) else None
  val prefetch = Bool()
  val v = Bool()

  def is_normalentry(): Bool = {
    if (!hasLevel) true.B
    else level.get === 2.U
  }

  def genPPN(vpn: UInt): UInt = {
    if (!hasLevel) ppn
    else MuxLookup(level.get, 0.U, Seq(
      0.U -> Cat(ppn(ppn.getWidth-1, vpnnLen*2), vpn(vpnnLen*2-1, 0)),
      1.U -> Cat(ppn(ppn.getWidth-1, vpnnLen), vpn(vpnnLen-1, 0)),
      2.U -> ppn)
    )
  }

  def hit(vpn: UInt, asid: UInt, allType: Boolean = false, ignoreAsid: Boolean = false) = {
    require(vpn.getWidth == vpnLen)
    //    require(this.asid.getWidth <= asid.getWidth)
    val asid_hit = if (ignoreAsid) true.B else (this.asid === asid)
    if (allType) {
      require(hasLevel)
      val hit0 = tag(tagLen - 1,    vpnnLen*2) === vpn(tagLen - 1, vpnnLen*2)
      val hit1 = tag(vpnnLen*2 - 1, vpnnLen)   === vpn(vpnnLen*2 - 1,  vpnnLen)
      val hit2 = tag(vpnnLen - 1,     0)         === vpn(vpnnLen - 1, 0)

      asid_hit && Mux(level.getOrElse(0.U) === 2.U, hit2 && hit1 && hit0, Mux(level.getOrElse(0.U) === 1.U, hit1 && hit0, hit0))
    } else if (hasLevel) {
      val hit0 = tag(tagLen - 1, tagLen - vpnnLen) === vpn(vpnLen - 1, vpnLen - vpnnLen)
      val hit1 = tag(tagLen - vpnnLen - 1, tagLen - vpnnLen * 2) === vpn(vpnLen - vpnnLen - 1, vpnLen - vpnnLen * 2)

      asid_hit && Mux(level.getOrElse(0.U) === 0.U, hit0, hit0 && hit1)
    } else {
      asid_hit && tag === vpn(vpnLen - 1, vpnLen - tagLen)
    }
  }

  def refill(vpn: UInt, asid: UInt, pte: UInt, level: UInt = 0.U, prefetch: Bool, valid: Bool = false.B) {
    require(this.asid.getWidth <= asid.getWidth) // maybe equal is better, but ugly outside

    tag := vpn(vpnLen - 1, vpnLen - tagLen)
    ppn := pte.asTypeOf(new PteBundle().cloneType).ppn
    perm.map(_ := pte.asTypeOf(new PteBundle().cloneType).perm)
    this.asid := asid
    this.prefetch := prefetch
    this.v := valid
    this.level.map(_ := level)
  }

  def genPtwEntry(vpn: UInt, asid: UInt, pte: UInt, level: UInt = 0.U, prefetch: Bool, valid: Bool = false.B) = {
    val e = Wire(new PtwEntry(tagLen, hasPerm, hasLevel))
    e.refill(vpn, asid, pte, level, prefetch, valid)
    e
  }



  override def toPrintable: Printable = {
    // p"tag:0x${Hexadecimal(tag)} ppn:0x${Hexadecimal(ppn)} perm:${perm}"
    p"tag:0x${Hexadecimal(tag)} ppn:0x${Hexadecimal(ppn)} " +
      (if (hasPerm) p"perm:${perm.getOrElse(0.U.asTypeOf(new PtePermBundle))} " else p"") +
      (if (hasLevel) p"level:${level.getOrElse(0.U)}" else p"") +
      p"prefetch:${prefetch}"
  }
}

class PtwEntries(num: Int, tagLen: Int, level: Int, hasPerm: Boolean) extends PtwBundle {
  require(log2Up(num)==log2Down(num))
  // NOTE: hasPerm means that is leaf or not.

  val tag  = UInt(tagLen.W)
  val asid = UInt(asidLen.W)
  val ppns = Vec(num, UInt(ppnLen.W))
  val vs   = Vec(num, Bool())
  val perms = if (hasPerm) Some(Vec(num, new PtePermBundle)) else None
  val prefetch = Bool()
  // println(s"PtwEntries: tag:1*${tagLen} ppns:${num}*${ppnLen} vs:${num}*1")
  // NOTE: vs is used for different usage:
  // for l3, which store the leaf(leaves), vs is page fault or not.
  // for l2, which shoule not store leaf, vs is valid or not, that will anticipate in hit check
  // Because, l2 should not store leaf(no perm), it doesn't store perm.
  // If l2 hit a leaf, the perm is still unavailble. Should still page walk. Complex but nothing helpful.
  // TODO: divide vs into validVec and pfVec
  // for l2: may valid but pf, so no need for page walk, return random pte with pf.

  def tagClip(vpn: UInt) = {
    require(vpn.getWidth == vpnLen)
    vpn(vpnLen - 1, vpnLen - tagLen)
  }

  def sectorIdxClip(vpn: UInt, level: Int) = {
    getVpnClip(vpn, level)(log2Up(num) - 1, 0)
  }

  def hit(vpn: UInt, asid: UInt, ignoreAsid: Boolean = false) = {
    val asid_hit = if (ignoreAsid) true.B else (this.asid === asid)
    asid_hit && tag === tagClip(vpn) && (if (hasPerm) true.B else vs(sectorIdxClip(vpn, level)))
  }

  def genEntries(vpn: UInt, asid: UInt, data: UInt, levelUInt: UInt, prefetch: Bool) = {
    require((data.getWidth / XLEN) == num,
      s"input data length must be multiple of pte length: data.length:${data.getWidth} num:${num}")

    val ps = Wire(new PtwEntries(num, tagLen, level, hasPerm))
    ps.tag := tagClip(vpn)
    ps.asid := asid
    ps.prefetch := prefetch
    for (i <- 0 until num) {
      val pte = data((i+1)*XLEN-1, i*XLEN).asTypeOf(new PteBundle)
      ps.ppns(i) := pte.ppn
      ps.vs(i)   := !pte.isPf(levelUInt) && (if (hasPerm) pte.isLeaf() else !pte.isLeaf())
      ps.perms.map(_(i) := pte.perm)
    }
    ps
  }

  override def toPrintable: Printable = {
    // require(num == 4, "if num is not 4, please comment this toPrintable")
    // NOTE: if num is not 4, please comment this toPrintable
    val permsInner = perms.getOrElse(0.U.asTypeOf(Vec(num, new PtePermBundle)))
    p"asid: ${Hexadecimal(asid)} tag:0x${Hexadecimal(tag)} ppns:${printVec(ppns)} vs:${Binary(vs.asUInt)} " +
      (if (hasPerm) p"perms:${printVec(permsInner)}" else p"")
  }
}


class PtwReq extends PtwBundle {
  val vpn = UInt(vpnLen.W)

  override def toPrintable: Printable = {
    p"vpn:0x${Hexadecimal(vpn)}"
  }
}

class PtwResp extends PtwBundle {
  val entry = new PtwEntry(tagLen = vpnLen, hasPerm = true, hasLevel = true)
  val pf = Bool()
  val af = Bool()

  def apply(pf: Bool, af: Bool, level: UInt, pte: PteBundle, vpn: UInt, asid: UInt) = {
    this.entry.level.map(_ := level)
    this.entry.tag := vpn
    this.entry.perm.map(_ := pte.getPerm())
    this.entry.ppn := pte.ppn
    this.entry.prefetch := DontCare
    this.entry.asid := asid
    this.entry.v := !pf
    this.pf := pf
    this.af := af
  }

  override def toPrintable: Printable = {
    p"entry:${entry} pf:${pf} af:${af}"
  }
}


class L2TLBIO extends PtwBundle {
  val tlb = Vec(PtwWidth, Flipped(new TlbPtwIO))
  //val sfence = Input(new SfenceBundle)
  val csr = new Bundle {
    val tlb = Input(new TlbCsrBundle)
    val distribute_csr = Flipped(new DistributedCSRIO)
  }
}

class L2TlbMemReqBundle extends PtwBundle {
  val addr = UInt(PAddrBits.W)
  val id = UInt(bMemID.W)
}

class L2TlbInnerBundle extends PtwReq {
  val source = UInt(bSourceWidth.W)
}


object ValidHoldBypass{
  def apply(infire: Bool, outfire: Bool, flush: Bool = false.B) = {
    val valid = RegInit(false.B)
    when (infire) { valid := true.B }
    when (outfire) { valid := false.B } // ATTENTION: order different with ValidHold
    when (flush) { valid := false.B } // NOTE: the flush will flush in & out, is that ok?
    valid || infire
  }
}