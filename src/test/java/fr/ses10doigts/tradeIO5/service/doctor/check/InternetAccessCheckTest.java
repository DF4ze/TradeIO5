package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternetAccessCheckTest {

    private static final List<String> HOSTS = List.of("a.example", "b.example", "c.example");

    private static InternetAccessCheck check(boolean ipUp, Set<String> resolvable) {
        return new InternetAccessCheck(HOSTS, new InternetAccessCheck.Probe() {
            @Override
            public boolean connect(String ip, int port, int timeoutMs) {
                return ipUp;
            }

            @Override
            public boolean resolve(String host) {
                return resolvable.contains(host);
            }
        });
    }

    private static CheckResult of(List<CheckResult> results, String subject) {
        return results.stream().filter(r -> r.subject().equals(subject)).findFirst().orElseThrow();
    }

    @Test
    void allGoodIsOk() {
        List<CheckResult> r = check(true, Set.copyOf(HOSTS)).run();
        assertEquals(DoctorStatus.OK, of(r, InternetAccessCheck.SUBJECT_IP).status());
        assertEquals(DoctorStatus.OK, of(r, InternetAccessCheck.SUBJECT_DNS).status());
    }

    @Test
    void dnsDownWithIpUpIsCritical() {
        List<CheckResult> r = check(true, Set.of()).run();
        assertEquals(DoctorStatus.OK, of(r, InternetAccessCheck.SUBJECT_IP).status());
        CheckResult dns = of(r, InternetAccessCheck.SUBJECT_DNS);
        assertEquals(DoctorStatus.CRIT, dns.status());
        assertTrue(dns.message().contains("DNS en panne"));
    }

    @Test
    void partialResolutionFailureIsWarning() {
        CheckResult dns = of(check(true, Set.of("a.example", "b.example")).run(), InternetAccessCheck.SUBJECT_DNS);
        assertEquals(DoctorStatus.WARN, dns.status());
        assertTrue(dns.message().contains("c.example"));
    }

    @Test
    void noInternetIsCriticalAndDnsNotEvaluated() {
        List<CheckResult> r = check(false, Set.of()).run();
        assertEquals(DoctorStatus.CRIT, of(r, InternetAccessCheck.SUBJECT_IP).status());
        assertEquals(DoctorStatus.UNKNOWN, of(r, InternetAccessCheck.SUBJECT_DNS).status());
    }

    @Test
    void emptyHostListIsUnknown() {
        InternetAccessCheck c = new InternetAccessCheck(List.of(), new InternetAccessCheck.Probe() {
            @Override
            public boolean connect(String ip, int port, int timeoutMs) {
                return true;
            }

            @Override
            public boolean resolve(String host) {
                return true;
            }
        });
        assertEquals(DoctorStatus.UNKNOWN, of(c.run(), InternetAccessCheck.SUBJECT_DNS).status());
    }
}
