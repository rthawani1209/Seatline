package dev.seatline.ledger.server;

import com.google.protobuf.ByteString;
import dev.seatline.ledger.raft.Entry;
import dev.seatline.ledger.raft.Message;
import dev.seatline.ledger.rpc.RaftMessage;

import java.util.ArrayList;
import java.util.List;

/** Converts Raft messages to and from their Protocol Buffers form. */
final class RaftWire {

    private RaftWire() {}

    static RaftMessage toProto(Message m) {
        RaftMessage.Builder b = RaftMessage.newBuilder().setFrom(m.from()).setTo(m.to()).setTerm(m.term());
        switch (m) {
            case Message.RequestVote rv -> b.setRequestVote(dev.seatline.ledger.rpc.RequestVote.newBuilder()
                    .setLastLogIndex(rv.lastLogIndex())
                    .setLastLogTerm(rv.lastLogTerm())
                    .setPreVote(rv.preVote()));
            case Message.VoteResponse vr -> b.setVoteResponse(dev.seatline.ledger.rpc.VoteResponse.newBuilder()
                    .setGranted(vr.granted())
                    .setPreVote(vr.preVote()));
            case Message.AppendEntries ae -> {
                var ab = dev.seatline.ledger.rpc.AppendEntries.newBuilder()
                        .setPrevLogIndex(ae.prevLogIndex())
                        .setPrevLogTerm(ae.prevLogTerm())
                        .setLeaderCommit(ae.leaderCommit())
                        .setReadSeq(ae.readSeq());
                for (Entry e : ae.entries()) {
                    ab.addEntries(dev.seatline.ledger.rpc.Entry.newBuilder()
                            .setTerm(e.term())
                            .setIndex(e.index())
                            .setTimeMillis(e.timeMillis())
                            .setData(ByteString.copyFrom(e.data())));
                }
                b.setAppendEntries(ab);
            }
            case Message.AppendResponse ar -> b.setAppendResponse(dev.seatline.ledger.rpc.AppendResponse.newBuilder()
                    .setSuccess(ar.success())
                    .setMatchIndex(ar.matchIndex())
                    .setRejectedPrevIndex(ar.rejectedPrevIndex())
                    .setConflictTerm(ar.conflictTerm())
                    .setConflictIndex(ar.conflictIndex())
                    .setReadSeq(ar.readSeq()));
            case Message.InstallSnapshot is -> b.setInstallSnapshot(dev.seatline.ledger.rpc.InstallSnapshot.newBuilder()
                    .setLastIndex(is.lastIndex())
                    .setLastTerm(is.lastTerm())
                    .setData(ByteString.copyFrom(is.data()))
                    .setReadSeq(is.readSeq()));
            case Message.SnapshotResponse sr -> b.setSnapshotResponse(dev.seatline.ledger.rpc.SnapshotResponse.newBuilder()
                    .setMatchIndex(sr.matchIndex())
                    .setReadSeq(sr.readSeq()));
        }
        return b.build();
    }

    static Message fromProto(RaftMessage p) {
        int from = p.getFrom();
        int to = p.getTo();
        long term = p.getTerm();
        return switch (p.getBodyCase()) {
            case REQUEST_VOTE -> {
                var rv = p.getRequestVote();
                yield new Message.RequestVote(from, to, term, rv.getLastLogIndex(), rv.getLastLogTerm(), rv.getPreVote());
            }
            case VOTE_RESPONSE -> {
                var vr = p.getVoteResponse();
                yield new Message.VoteResponse(from, to, term, vr.getGranted(), vr.getPreVote());
            }
            case APPEND_ENTRIES -> {
                var ae = p.getAppendEntries();
                List<Entry> entries = new ArrayList<>(ae.getEntriesCount());
                for (var e : ae.getEntriesList()) {
                    entries.add(new Entry(e.getTerm(), e.getIndex(), e.getTimeMillis(), e.getData().toByteArray()));
                }
                yield new Message.AppendEntries(from, to, term, ae.getPrevLogIndex(), ae.getPrevLogTerm(),
                        entries, ae.getLeaderCommit(), ae.getReadSeq());
            }
            case APPEND_RESPONSE -> {
                var ar = p.getAppendResponse();
                yield new Message.AppendResponse(from, to, term, ar.getSuccess(), ar.getMatchIndex(),
                        ar.getRejectedPrevIndex(), ar.getConflictTerm(), ar.getConflictIndex(), ar.getReadSeq());
            }
            case INSTALL_SNAPSHOT -> {
                var is = p.getInstallSnapshot();
                yield new Message.InstallSnapshot(from, to, term, is.getLastIndex(), is.getLastTerm(),
                        is.getData().toByteArray(), is.getReadSeq());
            }
            case SNAPSHOT_RESPONSE -> {
                var sr = p.getSnapshotResponse();
                yield new Message.SnapshotResponse(from, to, term, sr.getMatchIndex(), sr.getReadSeq());
            }
            case BODY_NOT_SET -> throw new IllegalArgumentException("raft message from node " + from + " has no body");
        };
    }
}
