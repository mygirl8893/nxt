/******************************************************************************
 * Copyright © 2013-2015 The Nxt Core Developers.                             *
 *                                                                            *
 * See the AUTHORS.txt, DEVELOPER-AGREEMENT.txt and LICENSE.txt files at      *
 * the top-level directory of this distribution for the individual copyright  *
 * holder information and the developer policies on copyright and licensing.  *
 *                                                                            *
 * Unless otherwise agreed in a custom licensing agreement, no part of the    *
 * Nxt software, including this file, may be copied, modified, propagated,    *
 * or distributed except according to the terms contained in the LICENSE.txt  *
 * file.                                                                      *
 *                                                                            *
 * Removal or modification of this copyright notice is prohibited.            *
 *                                                                            *
 ******************************************************************************/

package nxt;

import nxt.util.Convert;
import org.json.simple.JSONObject;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

public abstract class ShufflingTransaction extends TransactionType {

    private static final byte SUBTYPE_SHUFFLING_CREATION = 0;
    private static final byte SUBTYPE_SHUFFLING_REGISTRATION = 1;
    private static final byte SUBTYPE_SHUFFLING_PROCESSING = 2;
    private static final byte SUBTYPE_SHUFFLING_VERIFICATION = 3;
    private static final byte SUBTYPE_SHUFFLING_CANCELLATION = 4;

    static TransactionType findTransactionType(byte subtype) {
        switch (subtype) {
            case SUBTYPE_SHUFFLING_CREATION:
                return SHUFFLING_CREATION;
            case SUBTYPE_SHUFFLING_REGISTRATION:
                return SHUFFLING_REGISTRATION;
            case SUBTYPE_SHUFFLING_PROCESSING:
                return SHUFFLING_PROCESSING;
            case SUBTYPE_SHUFFLING_VERIFICATION:
                return SHUFFLING_VERIFICATION;
            case SUBTYPE_SHUFFLING_CANCELLATION:
                return SHUFFLING_CANCELLATION;
            default:
                return null;
        }
    }

    private ShufflingTransaction() {}

    @Override
    public final byte getType() {
        return TransactionType.TYPE_SHUFFLING;
    }

    @Override
    public final boolean isPhasingSafe() {
        return false;
    }

    //TODO: shuffle creation and registration phasable?
    @Override
    public final boolean isPhasable() {
        return false;
    }

    public static final TransactionType SHUFFLING_CREATION = new ShufflingTransaction() {

        @Override
        public byte getSubtype() {
            return SUBTYPE_SHUFFLING_CREATION;
        }

        @Override
        public AccountLedger.LedgerEvent getLedgerEvent() {
            return AccountLedger.LedgerEvent.SHUFFLING;
        }

        @Override
        public String getName() {
            return "ShufflingCreation";
        }

        @Override
        Attachment.AbstractAttachment parseAttachment(ByteBuffer buffer, byte transactionVersion) {
            return new Attachment.ShufflingCreation(buffer, transactionVersion);
        }

        @Override
        Attachment.AbstractAttachment parseAttachment(JSONObject attachmentData) {
            return new Attachment.ShufflingCreation(attachmentData);
        }

        @Override
        void validateAttachment(Transaction transaction) throws NxtException.ValidationException {
            if (Nxt.getBlockchain().getHeight() < Constants.SHUFFLING_BLOCK) {
                throw new NxtException.NotYetEnabledException("Shuffling not yet enabled");
            }
            Attachment.ShufflingCreation attachment = (Attachment.ShufflingCreation) transaction.getAttachment();
            HoldingType holdingType = attachment.getHoldingType();
            long amount = attachment.getAmount();
            if (holdingType == HoldingType.NXT) {
                if (amount < Constants.SHUFFLE_DEPOSIT_NQT || amount > Constants.MAX_BALANCE_NQT) {
                    throw new NxtException.NotValidException("Invalid NQT amount " + amount
                            + ", minimum is " + Constants.SHUFFLE_DEPOSIT_NQT);
                }
            } else if (holdingType == HoldingType.ASSET) {
                Asset asset = Asset.getAsset(attachment.getHoldingId());
                if (asset == null) {
                    throw new NxtException.NotCurrentlyValidException("Unknown asset " + Long.toUnsignedString(attachment.getHoldingId()));
                }
                if (amount <= 0 || amount > asset.getInitialQuantityQNT()) {
                    throw new NxtException.NotValidException("Invalid asset quantity " + amount);
                }
            } else if (holdingType == HoldingType.CURRENCY) {
                Currency currency = Currency.getCurrency(attachment.getHoldingId());
                CurrencyType.validate(currency, transaction);
                if (!currency.isActive()) {
                    throw new NxtException.NotCurrentlyValidException("Currency is not active: " + currency.getCode());
                }
                if (amount <= 0 || amount > Constants.MAX_CURRENCY_TOTAL_SUPPLY) {
                    throw new NxtException.NotValidException("Invalid currency amount " + amount);
                }
            } else {
                throw new RuntimeException("Unsupported holding type " + holdingType);
            }
            if (attachment.getParticipantCount() < Constants.MIN_NUMBER_OF_SHUFFLING_PARTICIPANTS
                    || attachment.getParticipantCount() > Constants.MAX_NUMBER_OF_SHUFFLING_PARTICIPANTS) {
                throw new NxtException.NotValidException(String.format("Number of participants %d is not between %d and %d",
                        attachment.getParticipantCount(), Constants.MIN_NUMBER_OF_SHUFFLING_PARTICIPANTS, Constants.MAX_NUMBER_OF_SHUFFLING_PARTICIPANTS));
            }
            //TODO: takes more than one block to complete, improve height check
            if (attachment.getCancellationHeight() <= Nxt.getBlockchain().getHeight()) {
                throw new NxtException.NotValidException(String.format("Cancellation height %d is smaller than current height %d",
                        attachment.getCancellationHeight(), Nxt.getBlockchain().getHeight()));
            }
        }

        @Override
        boolean applyAttachmentUnconfirmed(Transaction transaction, Account senderAccount) {
            Attachment.ShufflingCreation attachment = (Attachment.ShufflingCreation) transaction.getAttachment();
            HoldingType holdingType = attachment.getHoldingType();
            if (holdingType != HoldingType.NXT) {
                if (holdingType.getUnconfirmedBalance(senderAccount, attachment.getHoldingId()) >= attachment.getAmount()
                        && senderAccount.getUnconfirmedBalanceNQT() >= Constants.SHUFFLE_DEPOSIT_NQT) {
                    holdingType.addToUnconfirmedBalance(senderAccount, getLedgerEvent(), transaction.getId(), attachment.getHoldingId(), -attachment.getAmount());
                    senderAccount.addToUnconfirmedBalanceNQT(getLedgerEvent(), transaction.getId(), -Constants.SHUFFLE_DEPOSIT_NQT);
                    return true;
                }
            } else {
                if (senderAccount.getUnconfirmedBalanceNQT() >= attachment.getAmount()) {
                    senderAccount.addToUnconfirmedBalanceNQT(getLedgerEvent(), transaction.getId(), -attachment.getAmount());
                    return true;
                }
            }
            return false;
        }

        @Override
        void applyAttachment(Transaction transaction, Account senderAccount, Account recipientAccount) {
            Attachment.ShufflingCreation attachment = (Attachment.ShufflingCreation) transaction.getAttachment();
            Shuffling.addShuffling(transaction, attachment);
        }

        @Override
        void undoAttachmentUnconfirmed(Transaction transaction, Account senderAccount) {
            Attachment.ShufflingCreation attachment = (Attachment.ShufflingCreation) transaction.getAttachment();
            HoldingType holdingType = attachment.getHoldingType();
            if (holdingType != HoldingType.NXT) {
                holdingType.addToUnconfirmedBalance(senderAccount, getLedgerEvent(), transaction.getId(), attachment.getHoldingId(), attachment.getAmount());
                senderAccount.addToUnconfirmedBalanceNQT(getLedgerEvent(), transaction.getId(), Constants.SHUFFLE_DEPOSIT_NQT);
            } else {
                senderAccount.addToUnconfirmedBalanceNQT(getLedgerEvent(), transaction.getId(), attachment.getAmount());
            }
        }

        @Override
        boolean isDuplicate(Transaction transaction, Map<TransactionType, Map<String, Integer>> duplicates) {
            Attachment.ShufflingCreation attachment = (Attachment.ShufflingCreation) transaction.getAttachment();
            if (attachment.getHoldingType() != HoldingType.CURRENCY) {
                return false;
            }
            Currency currency = Currency.getCurrency(attachment.getHoldingId());
            String nameLower = currency.getName().toLowerCase();
            String codeLower = currency.getCode().toLowerCase();
            boolean isDuplicate = TransactionType.isDuplicate(MonetarySystem.CURRENCY_ISSUANCE, nameLower, duplicates, false);
            if (! nameLower.equals(codeLower)) {
                isDuplicate = isDuplicate || TransactionType.isDuplicate(MonetarySystem.CURRENCY_ISSUANCE, codeLower, duplicates, false);
            }
            return isDuplicate;
        }

        @Override
        public boolean canHaveRecipient() {
            return false;
        }
    };

    public static final TransactionType SHUFFLING_REGISTRATION = new ShufflingTransaction() {

        @Override
        public byte getSubtype() {
            return SUBTYPE_SHUFFLING_REGISTRATION;
        }

        @Override
        public AccountLedger.LedgerEvent getLedgerEvent() {
            return AccountLedger.LedgerEvent.SHUFFLING;
        }

        @Override
        public String getName() {
            return "ShufflingRegistration";
        }

        @Override
        Attachment.AbstractAttachment parseAttachment(ByteBuffer buffer, byte transactionVersion) {
            return new Attachment.ShufflingRegistration(buffer, transactionVersion);
        }

        @Override
        Attachment.AbstractAttachment parseAttachment(JSONObject attachmentData) {
            return new Attachment.ShufflingRegistration(attachmentData);
        }

        @Override
        void validateAttachment(Transaction transaction) throws NxtException.ValidationException {
            if (Nxt.getBlockchain().getHeight() < Constants.SHUFFLING_BLOCK) {
                throw new NxtException.NotYetEnabledException("Shuffling not yet enabled");
            }
            Attachment.ShufflingRegistration attachment = (Attachment.ShufflingRegistration) transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            if (shuffling == null) {
                throw new NxtException.NotCurrentlyValidException("Shuffling not found: " + Long.toUnsignedString(attachment.getShufflingId()));
            }
            if (shuffling.getStage() != Shuffling.Stage.REGISTRATION) {
                throw new NxtException.NotCurrentlyValidException("Shuffling registration has ended for " + Long.toUnsignedString(attachment.getShufflingId()));
            }
            if (shuffling.getParticipant(transaction.getSenderId()) != null) {
                throw new NxtException.NotCurrentlyValidException(String.format("Account %s is already registered for shuffling %s",
                        Convert.rsAccount(transaction.getSenderId()), Long.toUnsignedString(shuffling.getId())));
            }
        }

        @Override
        boolean isDuplicate(Transaction transaction, Map<TransactionType, Map<String, Integer>> duplicates) {
            Attachment.ShufflingRegistration attachment = (Attachment.ShufflingRegistration) transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            return TransactionType.isDuplicate(SHUFFLING_REGISTRATION,
                    Long.toUnsignedString(shuffling.getId()) + "." + Long.toUnsignedString(transaction.getSenderId()), duplicates, true)
                    || TransactionType.isDuplicate(SHUFFLING_REGISTRATION,
                    Long.toUnsignedString(shuffling.getId()), duplicates, shuffling.getParticipantCount() - ShufflingParticipant.getCount(shuffling.getId()));
        }

        @Override
        boolean applyAttachmentUnconfirmed(Transaction transaction, Account senderAccount) {
            Attachment.ShufflingRegistration attachment = (Attachment.ShufflingRegistration) transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            HoldingType holdingType = shuffling.getHoldingType();
            if (holdingType != HoldingType.NXT) {
                if (holdingType.getUnconfirmedBalance(senderAccount, shuffling.getHoldingId()) >= shuffling.getAmount()
                        && senderAccount.getUnconfirmedBalanceNQT() >= Constants.SHUFFLE_DEPOSIT_NQT) {
                    holdingType.addToUnconfirmedBalance(senderAccount, getLedgerEvent(), transaction.getId(), shuffling.getHoldingId(), -shuffling.getAmount());
                    senderAccount.addToUnconfirmedBalanceNQT(getLedgerEvent(), transaction.getId(), -Constants.SHUFFLE_DEPOSIT_NQT);
                    return true;
                }
            } else {
                if (senderAccount.getUnconfirmedBalanceNQT() >= shuffling.getAmount()) {
                    senderAccount.addToUnconfirmedBalanceNQT(getLedgerEvent(), transaction.getId(), -shuffling.getAmount());
                    return true;
                }
            }
            return false;
        }

        @Override
        void applyAttachment(Transaction transaction, Account senderAccount, Account recipientAccount) {
            Attachment.ShufflingRegistration attachment = (Attachment.ShufflingRegistration) transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            shuffling.addParticipant(transaction.getSenderId());
        }

        @Override
        void undoAttachmentUnconfirmed(Transaction transaction, Account senderAccount) {
            Attachment.ShufflingRegistration attachment = (Attachment.ShufflingRegistration) transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            HoldingType holdingType = shuffling.getHoldingType();
            if (holdingType != HoldingType.NXT) {
                holdingType.addToUnconfirmedBalance(senderAccount, getLedgerEvent(), transaction.getId(), shuffling.getHoldingId(), shuffling.getAmount());
                senderAccount.addToUnconfirmedBalanceNQT(getLedgerEvent(), transaction.getId(), Constants.SHUFFLE_DEPOSIT_NQT);
            } else {
                senderAccount.addToUnconfirmedBalanceNQT(getLedgerEvent(), transaction.getId(), shuffling.getAmount());
            }
        }

        @Override
        public boolean canHaveRecipient() {
            return false;
        }
    };

    public static final TransactionType SHUFFLING_PROCESSING = new ShufflingTransaction() {

        @Override
        public byte getSubtype() {
            return SUBTYPE_SHUFFLING_PROCESSING;
        }

        @Override
        public AccountLedger.LedgerEvent getLedgerEvent() {
            return AccountLedger.LedgerEvent.SHUFFLING;
        }

        @Override
        public String getName() {
            return "ShufflingProcessing";
        }

        @Override
        Attachment.AbstractAttachment parseAttachment(ByteBuffer buffer, byte transactionVersion) throws NxtException.NotValidException {
            return new Attachment.ShufflingProcessing(buffer, transactionVersion);
        }

        @Override
        Attachment.AbstractAttachment parseAttachment(JSONObject attachmentData) {
            return new Attachment.ShufflingProcessing(attachmentData);
        }

        @Override
        void validateAttachment(Transaction transaction) throws NxtException.ValidationException {
            if (Nxt.getBlockchain().getHeight() < Constants.SHUFFLING_BLOCK) {
                throw new NxtException.NotYetEnabledException("Shuffling not yet enabled");
            }
            Attachment.ShufflingProcessing attachment = (Attachment.ShufflingProcessing)transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            if (shuffling == null) {
                throw new NxtException.NotCurrentlyValidException("Shuffling not found: " + Long.toUnsignedString(attachment.getShufflingId()));
            }
            if (shuffling.getStage() != Shuffling.Stage.PROCESSING) {
                throw new NxtException.NotCurrentlyValidException(String.format("Shuffling %s is not in processing stage",
                        Long.toUnsignedString(attachment.getShufflingId())));
            }
            ShufflingParticipant participant = shuffling.getParticipant(transaction.getSenderId());
            if (participant == null) {
                throw new NxtException.NotCurrentlyValidException(String.format("Account %s is not registered for shuffling %s",
                        Convert.rsAccount(transaction.getSenderId()), Long.toUnsignedString(shuffling.getId())));
            }
            if (!participant.getState().canBecome(ShufflingParticipant.State.PROCESSED)) {
                throw new NxtException.NotCurrentlyValidException(String.format("Participant %s processing already complete",
                        Convert.rsAccount(transaction.getSenderId())));
            }
            if (participant.getAccountId() != shuffling.getAssigneeAccountId()) {
                throw new NxtException.NotCurrentlyValidException(String.format("Participant %s is not currently assigned to process shuffling %s",
                        Long.toUnsignedString(participant.getAccountId()), Long.toUnsignedString(shuffling.getId())));
            }
            ShufflingParticipant previousParticipant = participant.getPreviousParticipant();
            if (previousParticipant != null) {
                byte[] previousDataTransactionFullHash = previousParticipant.getDataTransactionFullHash();
                if (previousDataTransactionFullHash == null || !Arrays.equals(previousDataTransactionFullHash, attachment.getPreviousDataTransactionFullHash())) {
                    throw new NxtException.NotCurrentlyValidException("Previous data transaction full hash doesn't match");
                }
            }
            byte[][] data = attachment.getData();
            if (data.length > participant.getIndex() + 1 || data.length == 0) {
                throw new NxtException.NotValidException(String.format("Invalid number of encrypted data %d for participant number %d",
                        data.length, participant.getIndex()));
            }
            for (byte[] bytes : data) {
                if (bytes.length < 32) {
                    throw new NxtException.NotValidException("Invalid encrypted data length " + bytes.length);
                }
            }
        }

        @Override
        boolean isDuplicate(Transaction transaction, Map<TransactionType, Map<String, Integer>> duplicates) {
            Attachment.ShufflingProcessing attachment = (Attachment.ShufflingProcessing) transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            return TransactionType.isDuplicate(SHUFFLING_PROCESSING, Long.toUnsignedString(shuffling.getId()), duplicates, true);
        }

        @Override
        boolean applyAttachmentUnconfirmed(Transaction transaction, Account senderAccount) {
            return true;
        }

        @Override
        void applyAttachment(Transaction transaction, Account senderAccount, Account recipientAccount) {
            Attachment.ShufflingProcessing attachment = (Attachment.ShufflingProcessing)transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            shuffling.updateParticipantData(transaction, attachment);
        }

        @Override
        void undoAttachmentUnconfirmed(Transaction transaction, Account senderAccount) {}

        @Override
        public boolean canHaveRecipient() {
            return false;
        }
    };

    public static final TransactionType SHUFFLING_VERIFICATION = new ShufflingTransaction() {

        @Override
        public byte getSubtype() {
            return SUBTYPE_SHUFFLING_VERIFICATION;
        }

        @Override
        public AccountLedger.LedgerEvent getLedgerEvent() {
            return AccountLedger.LedgerEvent.SHUFFLING;
        }

        @Override
        public String getName() {
            return "ShufflingVerification";
        }

        @Override
        Attachment.AbstractAttachment parseAttachment(ByteBuffer buffer, byte transactionVersion) {
            return new Attachment.ShufflingVerification(buffer, transactionVersion);
        }

        @Override
        Attachment.AbstractAttachment parseAttachment(JSONObject attachmentData) {
            return new Attachment.ShufflingVerification(attachmentData);
        }

        @Override
        void validateAttachment(Transaction transaction) throws NxtException.ValidationException {
            if (Nxt.getBlockchain().getHeight() < Constants.SHUFFLING_BLOCK) {
                throw new NxtException.NotYetEnabledException("Shuffling not yet enabled");
            }
            Attachment.ShufflingVerification attachment = (Attachment.ShufflingVerification) transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            if (shuffling == null) {
                throw new NxtException.NotCurrentlyValidException("Shuffling not found: " + Long.toUnsignedString(attachment.getShufflingId()));
            }
            if (shuffling.getStage() != Shuffling.Stage.VERIFICATION) {
                throw new NxtException.NotCurrentlyValidException("Shuffling not in verification stage: " + Long.toUnsignedString(attachment.getShufflingId()));
            }
            ShufflingParticipant participant = shuffling.getParticipant(transaction.getSenderId());
            if (participant == null) {
                throw new NxtException.NotCurrentlyValidException(String.format("Account %s is not registered for shuffling %s",
                        Convert.rsAccount(transaction.getSenderId()), Long.toUnsignedString(shuffling.getId())));
            }
            if (!participant.getState().canBecome(ShufflingParticipant.State.VERIFIED)) {
                throw new NxtException.NotCurrentlyValidException(String.format("Shuffling participant %s in state %s cannot become verified",
                        Long.toUnsignedString(attachment.getShufflingId()), participant.getState()));
            }
            byte[] lastParticipantDataTransactionFullHash = shuffling.getLastParticipant().getDataTransactionFullHash();
            if (lastParticipantDataTransactionFullHash == null || !Arrays.equals(lastParticipantDataTransactionFullHash, attachment.getLastDataTransactionFullHash())) {
                throw new NxtException.NotCurrentlyValidException("Last participant data transaction hash doesn't match");
            }
        }

        @Override
        boolean isDuplicate(Transaction transaction, Map<TransactionType, Map<String, Integer>> duplicates) {
            Attachment.ShufflingVerification attachment = (Attachment.ShufflingVerification) transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            return TransactionType.isDuplicate(SHUFFLING_VERIFICATION,
                    Long.toUnsignedString(shuffling.getId()) + "." + Long.toUnsignedString(transaction.getSenderId()), duplicates, true);
        }

        @Override
        boolean applyAttachmentUnconfirmed(Transaction transaction, Account senderAccount) {
            return true;
        }

        @Override
        void applyAttachment(Transaction transaction, Account senderAccount, Account recipientAccount) {
            Attachment.ShufflingVerification attachment = (Attachment.ShufflingVerification) transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            shuffling.verify(transaction.getSenderId());
        }

        @Override
        void undoAttachmentUnconfirmed(Transaction transaction, Account senderAccount) {
        }

        @Override
        public boolean canHaveRecipient() {
            return false;
        }
    };

    public static final TransactionType SHUFFLING_CANCELLATION = new ShufflingTransaction() {

        @Override
        public byte getSubtype() {
            return SUBTYPE_SHUFFLING_CANCELLATION;
        }

        @Override
        public AccountLedger.LedgerEvent getLedgerEvent() {
            return AccountLedger.LedgerEvent.SHUFFLING;
        }

        @Override
        public String getName() {
            return "ShufflingCancellation";
        }

        @Override
        Attachment.AbstractAttachment parseAttachment(ByteBuffer buffer, byte transactionVersion) throws NxtException.NotValidException {
            return new Attachment.ShufflingCancellation(buffer, transactionVersion);
        }

        @Override
        Attachment.AbstractAttachment parseAttachment(JSONObject attachmentData) {
            return new Attachment.ShufflingCancellation(attachmentData);
        }

        @Override
        void validateAttachment(Transaction transaction) throws NxtException.ValidationException {
            if (Nxt.getBlockchain().getHeight() < Constants.SHUFFLING_BLOCK) {
                throw new NxtException.NotYetEnabledException("Shuffling not yet enabled");
            }
            Attachment.ShufflingCancellation attachment = (Attachment.ShufflingCancellation) transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            if (shuffling == null) {
                throw new NxtException.NotCurrentlyValidException("Shuffling not found: " + Long.toUnsignedString(attachment.getShufflingId()));
            }
            if (!shuffling.getStage().canBecome(Shuffling.Stage.BLAME)) {
                throw new NxtException.NotCurrentlyValidException(String.format("Shuffling in state %s cannot be cancelled", shuffling.getStage()));
            }
            long cancellingAccountId = attachment.getCancellingAccountId();
            if (cancellingAccountId != 0 && cancellingAccountId != shuffling.getCancellingAccountId()) {
                throw new NxtException.NotCurrentlyValidException(String.format("Shuffling %s is not currently being cancelled by account %s",
                        Long.toUnsignedString(shuffling.getId()), Long.toUnsignedString(cancellingAccountId)));
            }
            ShufflingParticipant participant = shuffling.getParticipant(transaction.getSenderId());
            if (participant == null) {
                throw new NxtException.NotCurrentlyValidException(String.format("Account %s is not registered for shuffling %s",
                        Convert.rsAccount(transaction.getSenderId()), Long.toUnsignedString(shuffling.getId())));
            }
            if (!participant.getState().canBecome(ShufflingParticipant.State.CANCELLED)) {
                throw new NxtException.NotCurrentlyValidException(String.format("Shuffling participant %s in state %s cannot submit cancellation",
                        Long.toUnsignedString(attachment.getShufflingId()), participant.getState()));
            }
            if (participant.getDataTransactionFullHash() == null || !Arrays.equals(participant.getDataTransactionFullHash(), attachment.getDataTransactionFullHash())) {
                throw new NxtException.NotCurrentlyValidException("Data transaction full hash doesn't match");
            }
            byte[][] keySeeds = attachment.getKeySeeds();
            if (keySeeds.length != 0 && keySeeds.length != shuffling.getParticipantCount() - participant.getIndex() - 1) {
                throw new NxtException.NotValidException("Invalid number of revealed keySeeds: " + keySeeds.length);
            }
            for (byte[] keySeed : keySeeds) {
                if (keySeed.length != 32) {
                    throw new NxtException.NotValidException("Invalid keySeed: " + Convert.toHexString(keySeed));
                }
            }
        }

        @Override
        boolean isDuplicate(Transaction transaction, Map<TransactionType, Map<String, Integer>> duplicates) {
            Attachment.ShufflingCancellation attachment = (Attachment.ShufflingCancellation) transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            return TransactionType.isDuplicate(SHUFFLING_VERIFICATION, // use VERIFICATION for unique type
                    Long.toUnsignedString(shuffling.getId()) + "." + Long.toUnsignedString(transaction.getSenderId()), duplicates, true);
        }

        @Override
        boolean applyAttachmentUnconfirmed(Transaction transaction, Account senderAccount) {
            return true;
        }

        @Override
        void applyAttachment(Transaction transaction, Account senderAccount, Account recipientAccount) {
            Attachment.ShufflingCancellation attachment = (Attachment.ShufflingCancellation) transaction.getAttachment();
            Shuffling shuffling = Shuffling.getShuffling(attachment.getShufflingId());
            ShufflingParticipant participant = ShufflingParticipant.getParticipant(shuffling.getId(), senderAccount.getId());
            shuffling.cancelBy(participant, attachment.getKeySeeds());
        }

        @Override
        void undoAttachmentUnconfirmed(Transaction transaction, Account senderAccount) {}

        @Override
        public boolean canHaveRecipient() {
            return false;
        }
    };

}
