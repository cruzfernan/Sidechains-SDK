package com.horizen.validation

import com.horizen.block.SidechainBlock
import com.horizen.params.NetworkParams
import com.horizen.SidechainHistory
import com.horizen.transaction.mainchain.SidechainCreation
import com.horizen.utils.{BytesUtils, WithdrawalEpochUtils}
import scorex.util.idToBytes
import scala.util.Try

class WithdrawalEpochValidator(params: NetworkParams) extends SidechainBlockValidator {
  override def validate(block: SidechainBlock, history: SidechainHistory): Try[Unit] = Try {
    if (block.id.equals(params.sidechainGenesisBlockId))
      validateGenesisBlock(block).get
    else
      validateBlock(block, history).get
  }


  private def validateGenesisBlock(block: SidechainBlock): Try[Unit] = Try {
    // Verify that block contains only 1 MC block reference with a valid Sidechain Creation info
    if(block.mainchainBlocks.size != 1)
      throw new IllegalArgumentException("Sidechain block validation failed for %s: genesis block should contain single MC block reference.".format(BytesUtils.toHexString(idToBytes(block.id))))

    val sidechainCreation = block.mainchainBlocks.head.sidechainRelatedAggregatedTransaction.get.mc2scTransactionsOutputs.get(0).asInstanceOf[SidechainCreation]
    if(sidechainCreation.withdrawalEpochLength() != params.withdrawalEpochLength)
      throw new IllegalArgumentException("Sidechain block validation failed for %s: genesis block contains different withdrawal epoch length than expected in configs.".format(BytesUtils.toHexString(idToBytes(block.id))))
  }

  private def validateBlock(block: SidechainBlock, history: SidechainHistory): Try[Unit] = Try {
    // TO DO: do we need to check that non-genesis SC blocks don't contain MC block references with AggTx with SidechainCreation outputs?
    // or we can relay on MC consensus which forbids to declare the same SC id twice?

    history.storage.blockInfoById(block.parentId) match {
      case Some(parentBlockInfo) => // Parent block is present
        val blockEpochInfo = WithdrawalEpochUtils.getWithdrawalEpochInfo(block, parentBlockInfo.withdrawalEpochInfo, params)
        if (blockEpochInfo.epoch > parentBlockInfo.withdrawalEpochInfo.epoch) { // epoch increased
          if (parentBlockInfo.withdrawalEpochInfo.index != params.withdrawalEpochLength) // parent index was not the last index of the block -> Block contains MC Block refs from different Epochs
            throw new IllegalArgumentException("Sidechain block %s contains MC Block references, that belong to different withdrawal epochs.".format(BytesUtils.toHexString(idToBytes(block.id))))

        } else { // epoch is the same
          if (blockEpochInfo.index == params.withdrawalEpochLength && block.transactions.nonEmpty) // Block is the last block of the epoch and contains SC Txs
            throw new IllegalArgumentException("Sidechain block %s is the last withdrawal epoch block, but contains Sidechain Transactions.".format(BytesUtils.toHexString(idToBytes(block.id))))
        }

      case None => // Parent block is missed -> Let it be processed inside history append logic.
    }
  }
}