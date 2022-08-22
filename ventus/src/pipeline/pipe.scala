package pipeline

import L1Cache.ICache._
import chisel3._
import chisel3.util._
import parameters._

class ICachePipeReq_np extends Bundle {
  val addr = UInt(32.W)
  val warpid = UInt(depth_warp.W)
}
class ICachePipeRsp_np extends Bundle{
  val addr = UInt(32.W)
  val data = UInt(32.W)
  val warpid = UInt(depth_warp.W)
  val status = UInt(2.W)
}

class pipe extends Module{
  val io = IO(new Bundle{
    val icache_req = (DecoupledIO(new ICachePipeReq_np))
    val icache_rsp = Flipped(DecoupledIO(new ICachePipeRsp_np))
    val externalFlushPipe = ValidIO(UInt(depth_warp.W))
    val dcache_req = DecoupledIO(new DCacheCoreReq_np)
    val dcache_rsp = Flipped(DecoupledIO(new DCacheCoreRsp_np))
    val shared_req = DecoupledIO(new ShareMemCoreReq_np)
    val shared_rsp = Flipped(DecoupledIO(new DCacheCoreRsp_np))
    val pc_reset = Input(Bool())
    val warpReq=Flipped(Decoupled(new warpReqData))
    val warpRsp=(Decoupled(new warpRspData))
    val wg_id_lookup=Output(UInt(depth_warp.W))
    val wg_id_tag=Input(UInt(TAG_WIDTH.W))
    val inst = if (SINGLE_INST) Some(Flipped(DecoupledIO(UInt(32.W)))) else None
  })
  val issue_stall=Wire(Bool())
  val flush=Wire(Bool())


  val warp_sche=Module(new warp_scheduler)
  //val pcfifo=Module(new PCfifo)
  val control=Module(new Control)
  val operand_collector=Module(new operandCollector)
  val issue=Module(new Issue)
  val alu=Module(new ALUexe)
  val valu=Module(new vALUexe)
  val fpu=Module(new FPUexe)
  val lsu=Module(new LSUexe)
  val sfu=Module(new SFUexe)
  val mul=Module(new vMULexe)
  val lsu2wb=Module(new LSU2WB)
  val wb=Module(new Writeback(6,5))

  val scoreb=VecInit(Seq.fill(num_warp)(Module(new Scoreboard).io))
  val ibuffer=Module(new instbuffer)
  val ibuffer2issue=Module(new ibuffer2issue)
  val exe_acq_reg=Module(new Queue(new CtrlSigs,1,pipe=true))
  val exe_data=Module(new Queue(new vExeData,1,pipe=true))
  val simt_stack=Module(new SIMT_STACK(num_thread))
  val branch_back=Module(new Branch_back)
  val csrfile=Module(new CSRexe())

  io.externalFlushPipe.valid:=warp_sche.io.flush.valid|warp_sche.io.flushCache.valid
  io.externalFlushPipe.bits:=Mux(warp_sche.io.flush.valid,warp_sche.io.flush.bits,warp_sche.io.flushCache.bits)

  warp_sche.io.pc_reset:=io.pc_reset
  warp_sche.io.branch<>branch_back.io.out
  //warp_sche.io.pc_icache_ready:=pcfifo.io.icachebuf_ready
  warp_sche.io.pc_ibuffer_ready:=ibuffer.io.ibuffer_ready
  warp_sche.io.wg_id_tag:=io.wg_id_tag
  io.wg_id_lookup:=warp_sche.io.wg_id_lookup
  warp_sche.io.pc_rsp.valid:=io.icache_rsp.valid
  warp_sche.io.pc_rsp.bits:=io.icache_rsp.bits
  warp_sche.io.pc_rsp.bits.status:=Mux(ibuffer.io.in.ready,io.icache_rsp.bits.status,1.U(2.W))

  warp_sche.io.pc_req<>io.icache_req
  warp_sche.io.warp_control<>issue.io.out_warpscheduler
  warp_sche.io.issued_warp.bits:=exe_data.io.enq.bits.ctrl.wid
  warp_sche.io.issued_warp.valid:=exe_data.io.enq.fire()
  warp_sche.io.scoreboard_busy:=(VecInit(scoreb.map(_.delay))).asUInt()

  csrfile.io.CTA2csr:=warp_sche.io.CTA2csr
  warp_sche.io.warpReq<>io.warpReq
  warp_sche.io.warpRsp<>io.warpRsp

  //flush:=(warp_sche.io.branch.fire()&warp_sche.io.branch.bits.jump) | ()
  flush:=warp_sche.io.flush.valid


  control.io.pc:=io.icache_rsp.bits.addr
  control.io.inst:=io.icache_rsp.bits.data
  control.io.wid:=io.icache_rsp.bits.warpid

  ibuffer.io.in.bits:=control.io.control
  ibuffer.io.in.valid:=io.icache_rsp.valid& !io.icache_rsp.bits.status(0)
  ibuffer.io.flush:=warp_sche.io.flush

  when(control.io.control.alu_fn===63.U & ibuffer.io.in.valid) {
    printf(p"undefined instructions at 0x${Hexadecimal(control.io.pc)} with 0x${Hexadecimal(control.io.inst)}\n")
  }

  if(SINGLE_INST){
    control.io.inst:=io.inst.get.bits
    control.io.wid:=0.U
    io.inst.map(_.ready:=ibuffer.io.in.ready)
    ibuffer.io.in.valid:=io.inst.get.valid
    when(io.inst.get.fire) {printf(p"${Hexadecimal(control.io.inst)}\n")}
  }

  io.icache_rsp.ready:=ibuffer.io.in.ready

  val ibuffer_ready=Wire(Vec(num_warp,Bool()))
  warp_sche.io.exe_busy:= ~ibuffer_ready.asUInt()

  for (i <- 0 until num_warp) {
    ibuffer2issue.io.in(i).bits:=ibuffer.io.out(i).bits
    ibuffer2issue.io.in(i).valid:=ibuffer.io.out(i).valid & warp_sche.io.warp_ready(i)
    ibuffer.io.out(i).ready:=ibuffer2issue.io.in(i).ready & warp_sche.io.warp_ready(i)
    if(SINGLE_INST) {ibuffer2issue.io.in(i).valid:=ibuffer.io.out(i).valid & !scoreb(i).delay
    ibuffer.io.out(i).ready:=ibuffer2issue.io.in(i).ready & !scoreb(i).delay}
    val ctrl=ibuffer.io.out(i).bits
    ibuffer_ready(i):=Mux(ctrl.sfu,sfu.io.in.ready,
      Mux(ctrl.fp,fpu.io.in.ready,
        Mux(ctrl.csr.orR(),csrfile.io.in.ready,
          Mux(ctrl.mem,lsu.io.lsu_req.ready,
            Mux(ctrl.isvec&ctrl.simt_stack&ctrl.simt_stack_op===0.U,valu.io.in.ready&simt_stack.io.branch_ctl.ready,
              Mux(ctrl.isvec&ctrl.simt_stack,simt_stack.io.branch_ctl.ready,
                Mux(ctrl.isvec,valu.io.in.ready,
                  Mux(ctrl.barrier,warp_sche.io.warp_control.ready,alu.io.in.ready))))))))
    //when(!ibuffer.io.out(i).valid){ibuffer_ready(i):=false.B}
    scoreb(i).ibuffer_if_ctrl:=ibuffer.io.out(i).bits
    scoreb(i).if_ctrl:= (exe_acq_reg.io.enq.bits)
    scoreb(i).wb_v_ctrl:=wb.io.out_v.bits
    scoreb(i).wb_x_ctrl:=wb.io.out_x.bits
    scoreb(i).fence_end:=lsu.io.fence_end(i)
    scoreb(i).if_fire:=false.B
    scoreb(i).wb_v_fire:=false.B
    scoreb(i).wb_x_fire:=false.B
    scoreb(i).br_ctrl:=false.B
    when(warp_sche.io.branch.fire()&(warp_sche.io.branch.bits.wid===i.asUInt())){scoreb(i).br_ctrl:=true.B}.
      elsewhen(warp_sche.io.warp_control.fire()&(warp_sche.io.warp_control.bits.ctrl.wid===i.asUInt())){scoreb(i).br_ctrl:=true.B}.
      elsewhen(simt_stack.io.complete.valid&(simt_stack.io.complete.bits===i.asUInt())){scoreb(i).br_ctrl:=true.B}
 }
  scoreb(exe_acq_reg.io.enq.bits.wid).if_fire:=(exe_acq_reg.io.enq.fire())
  scoreb(wb.io.out_x.bits.warp_id).wb_x_fire:=wb.io.out_x.fire()
  scoreb(wb.io.out_v.bits.warp_id).wb_v_fire:=wb.io.out_v.fire()

  exe_acq_reg.io.enq<>ibuffer2issue.io.out
  operand_collector.io.control:=exe_acq_reg.io.deq.bits//ibuffer2issue.io.out.bits
  operand_collector.io.writeVecCtrl<>wb.io.out_v
  operand_collector.io.writeScalarCtrl<>wb.io.out_x

  simt_stack.io.input_wid:=exe_acq_reg.io.deq.bits.wid//ibuffer2issue.io.out.bits.wid

  when(io.icache_req.fire()&(io.icache_req.bits.warpid===2.U)){
    //printf(p"wid=${io.icache_req.bits.warpid},pc=0x${Hexadecimal(io.icache_req.bits.addr)}\n")
  }
  when(io.icache_rsp.fire()&(io.icache_rsp.bits.warpid===2.U)){
    //printf(p"wid=${io.icache_rsp.bits.warpid},pc=0x${Hexadecimal(io.icache_rsp.bits.addr)},inst=0x${Hexadecimal(io.icache_rsp.bits.data)}\n")
  }
  when(exe_data.io.deq.fire()&(exe_data.io.deq.bits.ctrl.wid===2.U)){
    //printf(p"wid=${exe_data.io.deq.bits.ctrl.wid},pc=0x${Hexadecimal(exe_data.io.deq.bits.ctrl.pc)},inst=0x${Hexadecimal(exe_data.io.deq.bits.ctrl.inst)}\n")
  }
  when(io.dcache_req.fire()&io.dcache_req.bits.isWrite){
    //printf(p"${io.dcache_req.bits.instrId},writedata=0x${io.dcache_req.bits.data}\n")
  }
  //输出所有write mem的操作
  val wid_to_check = 0.U //exe_data.io.deq.bits.ctrl.wid===wid_to_check&
  when( exe_data.io.deq.fire()&exe_data.io.deq.bits.ctrl.mem_cmd===2.U){
    printf(p"${exe_data.io.deq.bits.ctrl.wid},0x${Hexadecimal(exe_data.io.deq.bits.ctrl.pc)},writedata=")
    exe_data.io.deq.bits.in3.foreach(x=>{printf(p"${Hexadecimal(x.asUInt)} ")})
    printf(p"mask ${exe_data.io.deq.bits.mask} at${Hexadecimal(exe_data.io.deq.bits.in1(0))},${Hexadecimal(exe_data.io.deq.bits.in2(0))}")
    printf(p"\n")
  }
  //输出所有发射的指令
  //when( exe_data.io.deq.fire()){
  //  printf(p"${exe_data.io.deq.bits.ctrl.wid},0x${Hexadecimal(exe_data.io.deq.bits.ctrl.pc)},writedata=")
  //  //exe_data.io.deq.bits.in3.foreach(x=>{printf(p"${Hexadecimal(x.asUInt)} ")})
  //  printf(p"mask ${exe_data.io.deq.bits.mask} with${Hexadecimal(exe_data.io.deq.bits.in1(0))},${Hexadecimal(exe_data.io.deq.bits.in2(0))}")
  //  printf(p"\n")
  //}

  //输出特定指令的操作数
  //when((exe_data.io.deq.bits.ctrl.wid===wid_to_check|exe_data.io.deq.bits.ctrl.wid===1.U)& exe_data.io.deq.fire() &exe_data.io.deq.bits.ctrl.sfu &exe_data.io.deq.bits.ctrl.alu_fn===IDecode.FN_FDIV){
  //when((exe_data.io.deq.bits.ctrl.wid===wid_to_check|exe_data.io.deq.bits.ctrl.wid===1.U)& exe_data.io.deq.fire()  &exe_data.io.deq.bits.ctrl.alu_fn===IDecode.FN_FNMSUB){
  //    printf(p"0x${Hexadecimal(exe_data.io.deq.bits.ctrl.pc)},${exe_data.io.deq.bits.ctrl.wid}writedata=")
  //  exe_data.io.deq.bits.in2.foreach(x=>{printf(p"${Hexadecimal(x.asUInt)} ")})
  //  exe_data.io.deq.bits.in1.foreach(x=>{printf(p"${Hexadecimal(x.asUInt)} ")})
  //  exe_data.io.deq.bits.in3.foreach(x=>{printf(p"${Hexadecimal(x.asUInt)} ")})
  //  exe_data.io.deq.bits.mask.foreach(x=>{printf(p"${Hexadecimal(x.asUInt)} ")})
  //  printf(p"\n")
  //}
  //输出写入向量寄存器的
  //when(wb.io.out_v.fire&wb.io.out_v.bits.warp_id===wid_to_check){
  //  printf(p"write${wb.io.out_v.bits.reg_idxw})")
  //  wb.io.out_v.bits.wb_wfd_rd.foreach(x=>printf(p" ${Hexadecimal(x.asUInt)} "))
  //  printf(p"with ${wb.io.out_v.bits.wfd_mask}\n")
  //}
  //输出写入标量寄存器的
  //when(wb.io.out_x.fire&wb.io.out_x.bits.warp_id===wid_to_check){
  //  printf(p"write${wb.io.out_x.bits.reg_idxw} ${wb.io.out_x.bits.wb_wxd_rd}\n")
  //}

  {
    exe_data.io.enq.bits.ctrl := exe_acq_reg.io.deq.bits//ibuffer2issue.io.out.bits
    exe_data.io.enq.bits.in1 := operand_collector.io.alu_src1
    exe_data.io.enq.bits.in2 := operand_collector.io.alu_src2
    exe_data.io.enq.bits.in3 := operand_collector.io.alu_src3
    exe_data.io.enq.bits.mask := (operand_collector.io.mask.zipWithIndex.map{case(x,y)=>x&simt_stack.io.out_mask(y)})
    exe_data.io.enq.valid:=exe_acq_reg.io.deq.valid//ibuffer2issue.io.out.valid
  }
  exe_acq_reg.io.deq.ready:=exe_data.io.enq.ready//ibuffer2issue.io.out.ready:=exe_data.io.enq.ready
  issue.io.in<>exe_data.io.deq

  issue.io.out_vALU<>valu.io.in
  issue.io.out_LSU<>lsu.io.lsu_req
  issue.io.out_sALU<>alu.io.in
  issue.io.out_CSR<>csrfile.io.in
  issue.io.out_SIMT<>simt_stack.io.branch_ctl
  issue.io.out_SFU<>sfu.io.in
  //simt_stack.io.branch_ctl<>Queue(issue.io.out_SIMT,1,flow = true)
  simt_stack.io.if_mask<>valu.io.out2simt_stack
  simt_stack.io.fetch_ctl<>branch_back.io.in1

  alu.io.out2br<>branch_back.io.in0

  issue.io.out_MUL<>mul.io.in

  issue.io.out_vFPU<>fpu.io.in
  fpu.io.rm:=csrfile.io.frm
  sfu.io.rm:=csrfile.io.frm
  csrfile.io.frm_wid:=fpu.io.in.bits.ctrl.wid

  lsu.io.dcache_rsp<>io.dcache_rsp
  lsu.io.dcache_req<>io.dcache_req
  lsu.io.lsu_rsp<>lsu2wb.io.lsu_rsp
  lsu.io.shared_rsp<>io.shared_rsp
  lsu.io.shared_req<>io.shared_req

  wb.io.in_x(0)<>alu.io.out
  wb.io.in_x(1)<>fpu.io.out_x
  wb.io.in_x(2)<>lsu2wb.io.out_x
  wb.io.in_x(3)<>csrfile.io.out
  wb.io.in_x(4)<>sfu.io.out_x
  wb.io.in_x(5)<>mul.io.out_x
  wb.io.in_v(0)<>valu.io.out
  wb.io.in_v(1)<>fpu.io.out_v
  wb.io.in_v(2)<>lsu2wb.io.out_v
  wb.io.in_v(3)<>sfu.io.out_v
  wb.io.in_v(4)<>mul.io.out_v

  issue_stall:=(~issue.io.in.ready).asBool()//scoreb.io.delay | issue.io.in.ready
}