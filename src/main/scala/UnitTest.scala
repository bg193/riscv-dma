package dma

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp, IdRange, AddressSet}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink.{TLROM, TLTestRAM}
import freechips.rocketchip.unittest.{UnitTest, UnitTestIO}
import testchipip.TLHelper

class DmaBackendTestDriver(
    val dstStart: Int, val srcStart: Int,
    val wordBytes: Int, val testWords: Seq[Long])
    (implicit p: Parameters) extends LazyModule {
  val node = TLHelper.makeClientNode(
    name = "test-driver",
    sourceId = IdRange(0, 1))
  lazy val module = new DmaBackendTestDriverModule(this)
}

class DmaBackendTestDriverModule(outer: DmaBackendTestDriver)
    extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val dma = new DmaIO
    val finished = Output(Bool())
    val start = Input(Bool())
  })

  val (s_init :: s_dma_req :: s_dma_resp ::
       s_check_req :: s_check_resp :: s_done :: Nil) = Enum(6)
  val state = RegInit(s_init)

  val dataBits = outer.wordBytes * 8
  val lgSize = log2Ceil(outer.wordBytes)
  val expectedData = VecInit(outer.testWords.map(w => w.U(dataBits.W)))
  val length = expectedData.size * outer.wordBytes

  io.dma.req.valid := state === s_dma_req
  io.dma.req.bits := DmaRequest(
    cmd = DmaRequest.DMA_CMD_COPY,
    source = outer.srcStart.U,
    dest = outer.dstStart.U,
    length = length.U)
  io.dma.resp.ready := state === s_dma_resp

  val (tl, edge) = outer.node.out(0)
  val (checkIdx, checkDone) = Counter(tl.d.fire(), expectedData.size)

  tl.a.valid := state === s_check_req
  tl.a.bits := edge.Get(
    fromSource = 0.U,
    toAddress = outer.dstStart.U + (checkIdx << lgSize.U),
    lgSize = lgSize.U)._2
  tl.d.ready := state === s_check_resp

  io.finished := state === s_done

  when (state === s_init && io.start) { state := s_dma_req }
  when (io.dma.req.fire()) { state := s_dma_resp }
  when (io.dma.resp.fire()) { state := s_check_req }
  when (tl.a.fire()) { state := s_check_resp }
  when (tl.d.fire()) { state := s_check_req }
  when (checkDone) { state := s_done }

  val nWords = 64 / dataBits
  val wordSel = ((outer.dstStart >> lgSize).U + checkIdx)(log2Ceil(nWords)-1,0)
  val actualData = MuxLookup(wordSel, 0.U, (0 until nWords).map(i =>
      i.U -> tl.d.bits.data(dataBits * (i + 1) - 1, dataBits * i)))

  assert(!tl.d.valid || actualData === expectedData(checkIdx))
}

class DmaBackendTest(implicit p: Parameters) extends LazyModule {
  val srcStart = 0x100
  val dstStart = 0x4
  val maxLength = 128
  val length = maxLength - (srcStart - 0x100)
  val wordBytes = 4
  val beatBytes = 8
  val srcOffset = (srcStart - 0x100) / wordBytes

  val testData = (0 until maxLength).map(i => i.toByte)
  val testWords = (0 until maxLength by wordBytes)
    .map(i =>
      (0 until wordBytes)
        .map(j => testData(i + j).toLong << (j * 8))
        .reduce(_ | _))
    .slice(srcOffset, length / 8)

  val driver = LazyModule(new DmaBackendTestDriver(
    dstStart, srcStart, wordBytes, testWords))
  val backend = LazyModule(new DmaBackend)
  val xbar = LazyModule(new TLXbar)
  val ram = LazyModule(new TLTestRAM(
    address = AddressSet(0, 0xff),
    beatBytes = beatBytes))
  val rom = LazyModule(new TLROM(
    base = 0x100,
    size = testData.size,
    contentsDelayed = testData,
    beatBytes = beatBytes))

  xbar.node := TLBuffer() := driver.node
  xbar.node := TLBuffer() := TLHintHandler() := backend.node
  ram.node := TLFragmenter(beatBytes, testData.size) := xbar.node
  rom.node := TLFragmenter(beatBytes, testData.size) := xbar.node

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle with UnitTestIO)
    backend.module.io.dma <> driver.module.io.dma
    driver.module.io.start := io.start
    io.finished := driver.module.io.finished
  }
}

class DmaBackendUnitTest(implicit p: Parameters) extends UnitTest {
  val test = Module(LazyModule(new DmaBackendTest).module)
  test.io.start := io.start
  io.finished := test.io.finished
}

object DmaUnitTests {
  def apply(implicit p: Parameters) = Seq(
    Module(new DmaBackendUnitTest))
}
