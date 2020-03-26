package com.horizen.block

import java.io.{BufferedReader, BufferedWriter, FileReader, FileWriter}

import com.horizen.companion.SidechainTransactionsCompanion
import com.horizen.fixtures.{CompanionsFixture, ForgerBoxFixture, MerkleTreeFixture, SidechainBlockFixture}
import com.horizen.params.{MainNetParams, NetworkParams, TestNetParams}
import com.horizen.proof.Signature25519
import com.horizen.utils.BytesUtils
import com.horizen.vrf.VRFKeyGenerator
import org.junit.Assert.{assertArrayEquals, assertEquals, assertFalse, assertNotEquals, assertTrue, fail => jFail}
import org.junit.Test
import org.scalatest.junit.JUnitSuite
import scorex.util.ModifierId

import scala.io.Source

class OmmerTest extends JUnitSuite with CompanionsFixture with SidechainBlockFixture {

  val sidechainTransactionsCompanion: SidechainTransactionsCompanion = getDefaultTransactionsCompanion
  val sidechainBlockSerializer = new SidechainBlockSerializer(sidechainTransactionsCompanion)

  val params: NetworkParams = MainNetParams()
  val mcBlockRef1: MainchainBlockReference = MainchainBlockReference.create(BytesUtils.fromHexString(Source.fromResource("mcblock473173_mainnet").getLines().next()), params).get
  val mcBlockRef2: MainchainBlockReference = MainchainBlockReference.create(BytesUtils.fromHexString(Source.fromResource("mcblock473174_mainnet").getLines().next()), params).get
  val mcBlockRef3: MainchainBlockReference = MainchainBlockReference.create(BytesUtils.fromHexString(Source.fromResource("mcblock473175_mainnet").getLines().next()), params).get
  val mcBlockRef4: MainchainBlockReference = MainchainBlockReference.create(BytesUtils.fromHexString(Source.fromResource("mcblock473176_mainnet").getLines().next()), params).get


  @Test
  def semanticValidity(): Unit = {
    // Test 1: ommer with valid SidechainBlockHeader and no headers&ommers at all must be valid
    // Create Block with no MainchainBlockReferencesData and MainchainHeaders
    var block: SidechainBlock = SidechainBlockFixture.generateSidechainBlock(sidechainTransactionsCompanion, basicSeed = 444L, timestampOpt = Some(10000L), includeReference = false)
    var ommer: Ommer = Ommer.toOmmer(block)
    assertTrue("Ommer expected to be semantically Valid.", ommer.semanticValidity(params))


    // Test 2: ommers with valid SidechainBlockHeader, without mc block references data but with defined RefDataHash must be invalid
    var invalidOmmer = ommer.copy(mainchainReferencesDataMerkleRootHashOption = Some(new Array[Byte](32)))
    assertFalse("Ommer expected to be semantically Invalid.", invalidOmmer.semanticValidity(params))


    // Test 3: ommer with valid SidechainBlockHeader, MainchainBlockReferencesData and MainchainHeaders must be valid
    val seed: Long = 11L
    val parentId: ModifierId = getRandomBlockId(seed)
    val (forgerBox, forgerMetadata) = ForgerBoxFixture.generateForgerBox(seed)
    val vrfProof = VRFKeyGenerator.generate(Array.fill(32)(seed.toByte))._1.prove(Array.fill(32)((seed + 1).toByte))
    // Create block with 1 MainchainBlockReferencesData and 2 MainchainHeader
    block = SidechainBlock.create(
      parentId,
      123444L,
      Seq(mcBlockRef1.data),
      Seq(),  // No txs
      Seq(mcBlockRef1.header, mcBlockRef2.header),
      Seq(),  // No ommers
      forgerMetadata.rewardSecret,
      forgerBox,
      vrfProof,
      MerkleTreeFixture.generateRandomMerklePath(seed),
      sidechainTransactionsCompanion,
      params
    ).get

    ommer = Ommer.toOmmer(block)
    assertTrue("Ommer expected to be semantically Valid.", ommer.semanticValidity(params))


    // Test 4: ommer with valid SidechainBlockHeader, MainchainBlockReferencesData and MainchainHeaders, but missed RefDataHash must be invalid
    invalidOmmer = ommer.copy(mainchainReferencesDataMerkleRootHashOption = None)
    assertFalse("Ommer expected to be semantically Invalid.", invalidOmmer.semanticValidity(params))


    // Test 5: ommer with SidechainBlockHeader, MainchainBlockReferencesData and MainchainHeaders, but invalid RefDataHash must be invalid
    invalidOmmer = ommer.copy(mainchainReferencesDataMerkleRootHashOption = Some(new Array[Byte](32)))
    assertFalse("Ommer expected to be semantically Invalid.", invalidOmmer.semanticValidity(params))


    // Test 6: ommer with valid SidechainBlockHeader and non consistent chain of MainchainHeaders must be invalid
    invalidOmmer = ommer.copy(mainchainHeaders = Seq(mcBlockRef1.header, mcBlockRef3.header, mcBlockRef4.header))
    assertFalse("Ommer expected to be semantically Invalid.", invalidOmmer.semanticValidity(params))


    // Test 7: ommer with valid SidechainBlockHeader but with inconsistent MainchainHeaders must be invalid
    // Without first MainchainHeaders
    invalidOmmer = ommer.copy(mainchainHeaders = ommer.mainchainHeaders.tail)
    assertFalse("Ommer expected to be semantically Invalid.", invalidOmmer.semanticValidity(params))

    // With one more MainchainHeader in the end
    invalidOmmer = ommer.copy(mainchainHeaders = ommer.mainchainHeaders :+ mcBlockRef3.header)
    assertFalse("Ommer expected to be semantically Invalid.", invalidOmmer.semanticValidity(params))


    // Test 8: ommer with valid SidechainBlockHeader that expects MainchainHeaders, but no data in ommer must be invalid
    // With no MainchainHeaders at all
    invalidOmmer = ommer.copy(mainchainHeaders = Seq())
    assertFalse("Ommer expected to be semantically Invalid.", invalidOmmer.semanticValidity(params))
    // With no MainchainBlockReferencesData and MainchainHeaders at all
    invalidOmmer = ommer.copy(mainchainHeaders = Seq(), mainchainReferencesDataMerkleRootHashOption = None)
    assertFalse("Ommer expected to be semantically Invalid.", invalidOmmer.semanticValidity(params))


    // Test 9: ommer with semantically invalid SidechainBlockHeader must be invalid
    // Broke block header signature
    invalidOmmer = ommer.copy(header = block.header.copy(signature = new Signature25519(new Array[Byte](Signature25519.SIGNATURE_LENGTH))))
    assertFalse("Ommer expected to be semantically Invalid.", invalidOmmer.semanticValidity(params))


    // Test 10: ommer with valid SidechainBlockHeader, but broken mcHeader
    // Broke mc header
    val invalidMcHeader1 = new MainchainHeader(
      mcBlockRef1.header.mainchainHeaderBytes,
      mcBlockRef1.header.version,
      mcBlockRef1.header.hashPrevBlock,
      mcBlockRef1.header.hashMerkleRoot,
      mcBlockRef1.header.hashSCMerkleRootsMap,
      -1, // broke time
      mcBlockRef1.header.bits,
      mcBlockRef1.header.nonce,
      mcBlockRef1.header.solution
    )

    invalidOmmer = ommer.copy(mainchainHeaders = Seq(invalidMcHeader1, mcBlockRef2.header))
    assertFalse("Ommer expected to be semantically Invalid.", invalidOmmer.semanticValidity(params))
  }

  @Test
  def compare(): Unit = {
    val block1: SidechainBlock = SidechainBlockFixture.generateSidechainBlock(sidechainTransactionsCompanion, basicSeed = 444L, timestampOpt = Some(10000L), includeReference = false)
    val ommer1: Ommer = Ommer.toOmmer(block1)

    // Test 1: Compare different ommers:
    val block2: SidechainBlock = SidechainBlockFixture.generateSidechainBlock(sidechainTransactionsCompanion, basicSeed = 555L, timestampOpt = Some(10000L), includeReference = false)
    val ommer2: Ommer = Ommer.toOmmer(block2)
    assertNotEquals("Ommers expected to be different", ommer1.hashCode(), ommer2.hashCode())
    assertNotEquals("Ommers expected to be different", ommer1, ommer2)


    // Test 2: Compare equal ommers:
    val block3: SidechainBlock = SidechainBlockFixture.generateSidechainBlock(sidechainTransactionsCompanion, basicSeed = 444L, timestampOpt = Some(10000L), includeReference = false)
    val ommer3: Ommer = Ommer.toOmmer(block3)
    assertEquals("Ommers expected to be equal", ommer1.hashCode(), ommer3.hashCode())
    assertEquals("Ommers expected to be equal", ommer1, ommer3)


    // Test 3: Compare with different class object
    val diffObject: Int = 1
    assertFalse("Object expected to be different", ommer1.equals(diffObject))
  }

  @Test
  def serialization(): Unit = {
    // Test 1: Ommer with SidechainBlockHeader and no MainchainBlockReferencesData and MainchainHeaders
    var block: SidechainBlock = SidechainBlockFixture.generateSidechainBlock(sidechainTransactionsCompanion, basicSeed = 444L, timestampOpt = Some(10000L), includeReference = false)
    var ommer: Ommer = Ommer.toOmmer(block)
    var bytes = ommer.bytes

    var serializedOmmerTry = OmmerSerializer.parseBytesTry(bytes)
    assertTrue("Ommer expected to by parsed.", serializedOmmerTry.isSuccess)

    var serializedOmmer = serializedOmmerTry.get
    assertEquals("Ommer sidechainBlockHeader is different", ommer.header.id, serializedOmmer.header.id)
    assertEquals("Ommer mainchainReferencesDataMerkleRootHashOption expected to be None",
      ommer.mainchainReferencesDataMerkleRootHashOption, serializedOmmer.mainchainReferencesDataMerkleRootHashOption)
    assertEquals("Ommer MainchainHeaders seq is different", ommer.mainchainHeaders, serializedOmmer.mainchainHeaders)
    assertEquals("Ommer ommers seq is different", ommer.ommers, serializedOmmer.ommers)
    assertArrayEquals("Ommer id is different", ommer.id, serializedOmmer.id)
    assertEquals("Ommer is different", ommer, serializedOmmer)


    // Test 2: ommer with MainchainBlockReferencesData and MainchainHeaders
    val seed: Long = 11L
    val parentId: ModifierId = getRandomBlockId(seed)
    val (forgerBox, forgerMetadata) = ForgerBoxFixture.generateForgerBox(seed)
    val vrfProof = VRFKeyGenerator.generate(Array.fill(32)(seed.toByte))._1.prove(Array.fill(32)((seed + 1).toByte))
    // Create block with 1 MainchainBlockReferencesData and 2 MainchainHeader
    block = SidechainBlock.create(
      parentId,
      123444L,
      Seq(mcBlockRef1.data),
      Seq(),  // No txs
      Seq(mcBlockRef1.header, mcBlockRef2.header),
      Seq(),  // No ommers
      forgerMetadata.rewardSecret,
      forgerBox,
      vrfProof,
      MerkleTreeFixture.generateRandomMerklePath(seed),
      sidechainTransactionsCompanion,
      params
    ).get

    ommer = Ommer.toOmmer(block)
    bytes = ommer.bytes

    // Set to true to regenerate regression data
    if(false) {
      val out = new BufferedWriter(new FileWriter("src/test/resources/ommer_hex"))
      out.write(BytesUtils.toHexString(bytes))
      out.close()
    }

    serializedOmmerTry = OmmerSerializer.parseBytesTry(bytes)
    assertTrue("Ommer expected to by parsed.", serializedOmmerTry.isSuccess)

    serializedOmmer = serializedOmmerTry.get
    assertEquals("Ommer sidechainBlockHeader is different", ommer.header.id, serializedOmmer.header.id)
    assertArrayEquals("Ommer mainchainReferencesDataMerkleRootHashOption is different",
      ommer.mainchainReferencesDataMerkleRootHashOption.get, serializedOmmer.mainchainReferencesDataMerkleRootHashOption.get)
    assertEquals("Ommer MainchainHeaders seq is different", ommer.mainchainHeaders, serializedOmmer.mainchainHeaders)
    assertEquals("Ommer ommers seq is different", ommer.ommers, serializedOmmer.ommers)
    assertArrayEquals("Ommer id is different", ommer.id, serializedOmmer.id)
    assertEquals("Ommer is different", ommer, serializedOmmer)

    // Test 3: try to deserialize broken bytes.
    assertTrue("OmmerSerializer expected to be not parsed due to broken data.", OmmerSerializer.parseBytesTry("broken bytes".getBytes).isFailure)
  }

  @Test
  def serializationRegression(): Unit = {
    var bytes: Array[Byte] = null
    try {
      val classLoader = getClass.getClassLoader
      val file = new FileReader(classLoader.getResource("ommer_hex").getFile)
      bytes = BytesUtils.fromHexString(new BufferedReader(file).readLine())
    }
    catch {
      case e: Exception =>
        jFail(e.toString)
    }


    val seed: Long = 11L
    val parentId: ModifierId = getRandomBlockId(seed)
    val (forgerBox, forgerMetadata) = ForgerBoxFixture.generateForgerBox(seed)
    val vrfProof = VRFKeyGenerator.generate(Array.fill(32)(seed.toByte))._1.prove(Array.fill(32)((seed + 1).toByte))
    // Create block with 1 MainchainBlockReferencesData and 2 MainchainHeader
    val block = SidechainBlock.create(
      parentId,
      123444L,
      Seq(mcBlockRef1.data),
      Seq(),  // No txs
      Seq(mcBlockRef1.header, mcBlockRef2.header),
      Seq(),  // No ommers
      forgerMetadata.rewardSecret,
      forgerBox,
      vrfProof,
      MerkleTreeFixture.generateRandomMerklePath(seed),
      sidechainTransactionsCompanion,
      params
    ).get

    val ommer = Ommer.toOmmer(block)

    val deserializedOmmerTry = OmmerSerializer.parseBytesTry(bytes)
    assertTrue("Ommer expected to by parsed.", deserializedOmmerTry.isSuccess)

    val deserializedOmmer = deserializedOmmerTry.get
    assertEquals("Ommer sidechainBlockHeader is different", ommer.header.id, deserializedOmmer.header.id)
    assertArrayEquals("Ommer mainchainReferencesDataMerkleRootHashOption is different",
      ommer.mainchainReferencesDataMerkleRootHashOption.get, deserializedOmmer.mainchainReferencesDataMerkleRootHashOption.get)
    assertEquals("Ommer MainchainHeaders seq is different", ommer.mainchainHeaders, deserializedOmmer.mainchainHeaders)
    assertEquals("Ommer ommers seq is different", ommer.ommers, deserializedOmmer.ommers)
    assertArrayEquals("Ommer id is different", ommer.id, deserializedOmmer.id)
    assertEquals("Ommer is different", ommer, deserializedOmmer)
  }
}
