package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2024 by Marcel Bokhorst (M66B)
*/

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.minidns.AbstractDnsClient;
import org.minidns.DnsClient;
import org.minidns.dane.DaneVerifier;
import org.minidns.dnsmessage.DnsMessage;
import org.minidns.dnsqueryresult.DnsQueryResult;
import org.minidns.dnsqueryresult.StandardDnsQueryResult;
import org.minidns.dnssec.DnssecClient;
import org.minidns.dnssec.DnssecResultNotAuthenticException;
import org.minidns.dnssec.DnssecValidationFailedException;
import org.minidns.dnsserverlookup.AbstractDnsServerLookupMechanism;
import org.minidns.hla.DnssecResolverApi;
import org.minidns.hla.ResolverApi;
import org.minidns.hla.ResolverResult;
import org.minidns.record.A;
import org.minidns.record.AAAA;
import org.minidns.record.Data;
import org.minidns.record.MX;
import org.minidns.record.NS;
import org.minidns.record.Record;
import org.minidns.record.SRV;
import org.minidns.record.TXT;
import org.minidns.source.AbstractDnsDataSource;
import org.minidns.util.MultipleIoException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;

public class DnsHelper {
    // https://dns.watch/
    private static final String DEFAULT_DNS = "84.200.69.80";
    private static final int CHECK_TIMEOUT = 5; // seconds
    private static final int LOOKUP_TIMEOUT = 15; // seconds

    static void checkMx(Context context, Address[] addresses) throws UnknownHostException {
        if (addresses == null)
            return;

        for (Address address : addresses) {
            String email = ((InternetAddress) address).getAddress();
            String domain = UriHelper.getEmailDomain(email);
            if (domain == null)
                continue;
            DnsRecord[] records = _lookup(context, domain, "mx", CHECK_TIMEOUT, false);
            if (records.length == 0)
                throw new UnknownHostException(domain);
        }
    }

    @NonNull
    static DnsRecord[] lookup(Context context, String name, String type) {
        return _lookup(context, name, type, LOOKUP_TIMEOUT, false);
    }

    @NonNull
    private static DnsRecord[] _lookup(
            Context context, String name, String type, int timeout, boolean require_authentic) {
        String filter = null;
        int colon = type.indexOf(':');
        if (colon > 0) {
            filter = type.substring(colon + 1);
            type = type.substring(0, colon);
        }

        Class<? extends Data> clazz;
        switch (type) {
            case "ns":
                clazz = NS.class;
                break;
            case "mx":
                clazz = MX.class;
                break;
            case "srv":
                clazz = SRV.class;
                break;
            case "txt":
                clazz = TXT.class;
                break;
            case "a":
                clazz = A.class;
                break;
            case "aaaa":
                clazz = AAAA.class;
                break;
            default:
                throw new IllegalArgumentException(type);
        }

        try {
            ResolverApi resolver = DnssecResolverApi.INSTANCE;
            AbstractDnsClient client = resolver.getClient();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                client.setDataSource(new AbstractDnsDataSource() {
                    private IOException ex;
                    private DnsQueryResult result;

                    @Override
                    public DnsQueryResult query(DnsMessage query, InetAddress address, int port) throws IOException {
                        Semaphore sem = new Semaphore(0);
                        DnsResolver resolver = DnsResolver.getInstance();
                        Log.i("DNS Android query=" + query);
                        resolver.rawQuery(
                                null,
                                query.toArray(),
                                DnsResolver.FLAG_EMPTY,
                                new Executor() {
                                    @Override
                                    public void execute(Runnable command) {
                                        command.run();
                                    }
                                },
                                null,
                                new DnsResolver.Callback<byte[]>() {
                                    @Override
                                    public void onAnswer(@NonNull byte[] bytes, int rcode) {
                                        try {
                                            DnsMessage answer = new DnsMessage(bytes)
                                                    .asBuilder()
                                                    .setResponseCode(DnsMessage.RESPONSE_CODE.getResponseCode(rcode))
                                                    .build();
                                            result = new StandardDnsQueryResult(
                                                    address, port,
                                                    DnsQueryResult.QueryMethod.udp,
                                                    query,
                                                    answer);
                                        } catch (Throwable e) {
                                            ex = new IOException(e.getMessage(), e);
                                        } finally {
                                            sem.release();
                                        }
                                    }

                                    @Override
                                    public void onError(@NonNull DnsResolver.DnsException e) {
                                        try {
                                            ex = new IOException(e.getMessage(), e);
                                        } finally {
                                            sem.release();
                                        }
                                    }
                                });

                        try {
                            if (!sem.tryAcquire(timeout, TimeUnit.SECONDS))
                                ex = new IOException("timeout");
                        } catch (InterruptedException e) {
                            ex = new IOException("interrupted");
                        }

                        if (ex == null) {
                            Log.i("DNS Android answer=" + result);
                            return result;
                        } else {
                            Log.i(ex);
                            throw ex;
                        }
                    }
                });

            client.getDataSource().setTimeout(timeout * 1000);

            List<String> servers = getDnsServers(context);
            Log.i("DNS servers=" + TextUtils.join(",", servers));

            DnsClient.addDnsServerLookupMechanism(
                    new AbstractDnsServerLookupMechanism("FairEmail", 1) {
                        @Override
                        public boolean isAvailable() {
                            return (servers.size() > 0);
                        }

                        @Override
                        public List<String> getDnsServerAddresses() {
                            return servers;
                        }
                    });

            // https://github.com/MiniDNS/minidns/issues/102
            if (client instanceof DnssecClient && !BuildConfig.PLAY_STORE_RELEASE)
                ((DnssecClient) client).setUseHardcodedDnsServers(false);

            Log.i("DNS query name=" + type + ":" + name);
            ResolverResult<? extends Data> data = resolver.resolve(name, clazz);
            data.throwIfErrorResponse();

            boolean secure = (data.getUnverifiedReasons() != null);
            if (secure && require_authentic) {
                DnssecResultNotAuthenticException ex = data.getDnssecResultNotAuthenticException();
                if (ex != null)
                    throw ex;
            }

            List<DnsRecord> result = new ArrayList<>();

            DnsMessage raw = data.getRawAnswer();
            List<Record<? extends Data>> answers = (raw == null ? null : raw.answerSection);
            Log.i("DNS answers=" + (answers == null ? "n/a" : answers.size()));
            if (answers != null) {
                Record.TYPE expectedType = data.getQuestion().type;
                for (Record<? extends Data> record : answers) {
                    if (record.type != expectedType) {
                        Log.i("DNS skip=" + record);
                        continue;
                    }

                    Data answer = record.getPayload();
                    Log.i("DNS record=" + record + " answer=" + answer);

                    if (answer instanceof NS) {
                        NS ns = (NS) answer;
                        result.add(new DnsRecord(ns.getTarget().toString()));
                    } else if (answer instanceof MX) {
                        MX mx = (MX) answer;
                        result.add(new DnsRecord(mx.target.toString(), 0, mx.priority, 0));
                    } else if (answer instanceof SRV) {
                        SRV srv = (SRV) answer;
                        result.add(new DnsRecord(srv.target.toString(), srv.port, srv.priority, srv.weight));
                    } else if (answer instanceof TXT) {
                        StringBuilder sb = new StringBuilder();
                        TXT txt = (TXT) answer;
                        for (String text : txt.getCharacterStrings()) {
                            if (filter != null &&
                                    (TextUtils.isEmpty(text) || !text.toLowerCase(Locale.ROOT).startsWith(filter)))
                                continue;
                            int i = 0;
                            int slash = text.indexOf('\\', i);
                            while (slash >= 0 && slash + 4 < text.length()) {
                                String digits = text.substring(slash + 1, slash + 4);
                                if (TextUtils.isDigitsOnly(digits)) {
                                    int k = Integer.parseInt(digits);
                                    text = text.substring(0, slash) + (char) k + text.substring(slash + 4);
                                } else
                                    i += 4;
                                slash = text.indexOf('\\', i);
                            }
                            sb.append(text);
                        }
                        result.add(new DnsRecord(sb.toString(), 0));
                    } else if (answer instanceof A) {
                        A a = (A) answer;
                        result.add(new DnsRecord(a.getInetAddress()));
                    } else if (answer instanceof AAAA) {
                        AAAA aaaa = (AAAA) answer;
                        result.add(new DnsRecord(aaaa.getInetAddress()));
                    } else
                        throw new IllegalArgumentException(answer.getClass().getName());

                }
            }

            for (DnsRecord record : result) {
                record.query = name;
                record.secure = secure;
                record.authentic = data.isAuthenticData();
            }

            if ("mx".equals(type) || "srv".equals(type))
                Collections.sort(result, new Comparator<DnsRecord>() {
                    @Override
                    public int compare(DnsRecord d1, DnsRecord d2) {
                        int o = Integer.compare(
                                d1.priority == null ? 0 : d1.priority,
                                d2.priority == null ? 0 : d2.priority);
                        if (o == 0)
                            o = Integer.compare(
                                    d1.weight == null ? 0 : d1.weight,
                                    d2.weight == null ? 0 : d2.weight);
                        return o;
                    }
                });

            return result.toArray(new DnsRecord[0]);
        } catch (Throwable ex) {
            if (ex instanceof DnssecValidationFailedException ||
                    ex instanceof MultipleIoException)
                Log.i(ex);
            else
                Log.e(ex);
            return new DnsRecord[0];
        }
    }

    private static List<String> getDnsServers(Context context) {
        List<String> result = new ArrayList<>();
        result.add(DEFAULT_DNS);

        ConnectivityManager cm = Helper.getSystemService(context, ConnectivityManager.class);
        if (cm == null)
            return result;

        Network active = ConnectionHelper.getActiveNetwork(context);
        if (active == null)
            return result;

        LinkProperties props = cm.getLinkProperties(active);
        if (props == null)
            return result;

        List<InetAddress> dns = props.getDnsServers();
        for (int i = 0; i < dns.size(); i++)
            result.add(i, dns.get(i).getHostAddress());

        return result;
    }

    static void verifyDane(X509Certificate[] chain, String server, int port) throws CertificateException {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                Log.w("DANE " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() throws SecurityException {
            }
        };
        String clazz = DaneVerifier.class.getName();
        Logger.getLogger(clazz).addHandler(handler);
        Log.w("DANE verify " + server + ":" + port);
        boolean verified = new DaneVerifier().verifyCertificateChain(chain, server, port);
        Log.w("DANE verified=" + verified + " " + server + ":" + port);
        Logger.getLogger(clazz).removeHandler(handler);
        if (!verified)
            throw new CertificateException("DANE missing or invalid");
    }

    static void test(Context context) throws UnknownHostException {
        test(context, "gmail.com", "ns");
        test(context, "gmail.com", "mx");
        test(context, "_imaps._tcp.gmail.com", "srv");
        test(context, "gmail.com", "txt");
        test(context, "outlook.office365.com", "a");
        test(context, "outlook.office365.com", "aaaa");
        test(context, "posteo.de", "a");
        test(context, "non.existent.tld", "a");
        test(context, "rubbish", "a");
    }

    private static void test(Context context, String name, String type) {
        DnsRecord[] records = lookup(context, name, type);
        Log.w("DNS test " + name + ":" + type);
        for (DnsRecord record : records)
            Log.w("- " + record);

    }

    static class DnsRecord {
        String query;
        String response;
        Integer port;
        Integer priority;
        Integer weight;
        Boolean secure;
        Boolean authentic;
        InetAddress address;

        DnsRecord(String response) {
            this.response = response;
        }

        DnsRecord(InetAddress address) {
            this.address = address;
            this.response = address.getHostAddress();
        }

        DnsRecord(String response, int port) {
            this.response = response;
            this.port = port;
        }

        DnsRecord(String response, int port, int priority, int weight) {
            this.response = response;
            this.port = port;
            this.priority = priority;
            this.weight = weight;
        }

        @NonNull
        @Override
        public String toString() {
            return query + "=" + response + ":" + port + " " + priority + "/" + weight +
                    " secure=" + secure + " authentic=" + authentic;
        }
    }
}
