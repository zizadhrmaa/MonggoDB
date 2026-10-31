package monggodb.ccm.serializability;

import java.util.List;

/** Verifier conflict serializability (CC-09) yang berdiri sendiri tanpa state CCM */
public interface ScheduleVerifier {

    /**
     * Menyusun precedence graph dari transaksi yang commit lalu mencari urutan serial lewat topological sort
     * @param schedule schedule terurut menurut seq, hasil rekaman driver atau ditulis manual
     * @return serializable beserta urutan serial, atau tidak serializable beserta siklusnya
     * @throws monggodb.contracts.shared.CCMException INVALID_REQUEST jika schedule tidak valid
     * @calls tidak ada
     * @calledBy driver CCM; runScenario
     */
    VerificationResult verifySchedule(List<ScheduleOp> schedule);
}
