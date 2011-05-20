/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2011
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4che.tool.movescu;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.OptionGroup;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.dcm4che.data.Attributes;
import org.dcm4che.data.ElementDictionary;
import org.dcm4che.data.Tag;
import org.dcm4che.data.UID;
import org.dcm4che.data.VR;
import org.dcm4che.io.DicomInputStream;
import org.dcm4che.net.ApplicationEntity;
import org.dcm4che.net.Association;
import org.dcm4che.net.Connection;
import org.dcm4che.net.Device;
import org.dcm4che.net.DimseRSPHandler;
import org.dcm4che.net.pdu.AAssociateRQ;
import org.dcm4che.net.pdu.ExtendedNegotiation;
import org.dcm4che.net.pdu.PresentationContext;
import org.dcm4che.tool.common.CLIUtils;
import org.dcm4che.util.SafeClose;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 *
 */
public class Main {

    private static final int RELATIONAL_RETRIEVE = 1;
    public static final String CompositeInstanceRootRetrieveMOVE = "1.2.840.10008.5.1.4.1.2.4.2";

    private static enum SOPClass {
        PatientRoot(UID.PatientRootQueryRetrieveInformationModelMOVE, 0),
        StudyRoot(UID.StudyRootQueryRetrieveInformationModelMOVE, 0),
        PatientStudyOnly(
                UID.PatientStudyOnlyQueryRetrieveInformationModelMOVERetired, 0),
        CompositeInstanceRoot(UID.CompositeInstanceRootRetrieveMOVE, 3),
        HangingProtocol(UID.HangingProtocolInformationModelMOVE, 3),
        ColorPalette(UID.ColorPaletteInformationModelMOVE, 3);

        final String cuid;
        final int defExtNeg;

        SOPClass(String cuid, int defExtNeg) {
            this.cuid = cuid;
            this.defExtNeg = defExtNeg;
        }
    }

    private static ResourceBundle rb =
        ResourceBundle.getBundle("org.dcm4che.tool.movescu.messages");

    private static String[] IVR_LE_FIRST = {
        UID.ImplicitVRLittleEndian,
        UID.ExplicitVRLittleEndian,
        UID.ExplicitVRBigEndian
    };

    private static String[] EVR_LE_FIRST = {
        UID.ExplicitVRLittleEndian,
        UID.ExplicitVRBigEndian,
        UID.ImplicitVRLittleEndian
    };

    private static String[] EVR_BE_FIRST = {
        UID.ExplicitVRBigEndian,
        UID.ExplicitVRLittleEndian,
        UID.ImplicitVRLittleEndian
    };

    private static String[] IVR_LE_ONLY = {
        UID.ImplicitVRLittleEndian
    };

    private final Device device = new Device("movescu");
    private final ApplicationEntity ae = new ApplicationEntity("MOVESCU");
    private final Connection conn = new Connection();
    private final Connection remote = new Connection();
    private final AAssociateRQ rq = new AAssociateRQ();
    private int priority;
    private String destination;
    private SOPClass sopClass = SOPClass.StudyRoot;
    private String[] tss = IVR_LE_FIRST;
    private int extNeg;

    private Attributes keys = new Attributes();

    private Association as;



    public Main() {
        device.addConnection(conn);
        device.addApplicationEntity(ae);
        ae.addConnection(conn);
    }

    public void setScheduledExecutorService(ScheduledExecutorService service) {
        device.setScheduledExecutor(service);
    }

    public void setExecutor(Executor executor) {
        device.setExecutor(executor);
    }

    public final void setPriority(int priority) {
        this.priority = priority;
    }

    public final void setSOPClass(SOPClass sopClass) {
        this.sopClass = sopClass;
    }

    public final void setTransferSyntaxes(String[] tss) {
        this.tss = tss.clone();
    }

    public final void setDestination(String destination) {
        this.destination = destination;
    }

    private void setExtendedNegotiation(int flag, boolean enable) {
        if (enable)
            extNeg |= flag;
        else
            extNeg &= ~flag;
    }

    public void setRelationalQueries(boolean enable) {
        setExtendedNegotiation(RELATIONAL_RETRIEVE, enable);
    }

    public void addKey(int tag, String value) {
        VR vr = ElementDictionary.vrOf(tag,
                keys.getPrivateCreator(tag));
        keys.setString(tag, vr, value);
    }

    private static CommandLine parseComandLine(String[] args)
                throws ParseException {
            Options opts = new Options();
            addServiceClassOptions(opts);
            addTransferSyntaxOptions(opts);
            addExtendedNegotionOptions(opts);
            addKeyOptions(opts);
            addRetrieveLevelOption(opts);
            addDestinationOption(opts);
            CLIUtils.addConnectOption(opts);
            CLIUtils.addBindOption(opts, "MOVESCU");
            CLIUtils.addAEOptions(opts, true, false);
            CLIUtils.addDimseRspOption(opts);
            CLIUtils.addPriorityOption(opts);
            CLIUtils.addCommonOptions(opts);
            return CLIUtils.parseComandLine(args, opts, rb, Main.class);
    }

    @SuppressWarnings("static-access")
    private static void addRetrieveLevelOption(Options opts) {
        opts.addOption(OptionBuilder
                .hasArg()
                .withArgName("PATIENT|STUDY|SERIES|IMAGE|FRAME")
                .withDescription(rb.getString("level"))
                .create("L"));
   }

    @SuppressWarnings("static-access")
    private static void addDestinationOption(Options opts) {
        opts.addOption(OptionBuilder
                .withLongOpt("dest")
                .hasArg()
                .withArgName("aet")
                .withDescription(rb.getString("dest"))
                .create());
        
    }

    @SuppressWarnings("static-access")
    private static void addKeyOptions(Options opts) {
        opts.addOption(OptionBuilder
                .hasArgs()
                .withArgName("attr=value")
                .withValueSeparator('=')
                .withDescription(rb.getString("match"))
                .create("m"));
    }

    @SuppressWarnings("static-access")
    private static void addServiceClassOptions(Options opts) {
        OptionGroup group = new OptionGroup();
        group.addOption(OptionBuilder
                .withLongOpt("patient-root")
                .withDescription(rb.getString("patient-root"))
                .create("P"));
        group.addOption(OptionBuilder
                .withLongOpt("study-root")
                .withDescription(rb.getString("study-root"))
                .create("S"));
        group.addOption(OptionBuilder
                .withLongOpt("patient-study-only")
                .withDescription(rb.getString("patient-study-only"))
                .create("O"));
        group.addOption(OptionBuilder
                .withLongOpt("instance-root")
                .withDescription(rb.getString("instance-root"))
                .create("I"));
        group.addOption(OptionBuilder
                .withLongOpt("hanging-protocol")
                .withDescription(rb.getString("hanging-protocol"))
                .create("H"));
        group.addOption(OptionBuilder
                .withLongOpt("color-palette")
                .withDescription(rb.getString("color-palette"))
                .create("C"));
        opts.addOptionGroup(group);
    }

    private static void addExtendedNegotionOptions(Options opts) {
        opts.addOption(null, "relational", false, rb.getString("relational"));
    }

    @SuppressWarnings("static-access")
    private static void addTransferSyntaxOptions(Options opts) {
        OptionGroup group = new OptionGroup();
        group.addOption(OptionBuilder
                .withLongOpt("explicit-vr")
                .withDescription(rb.getString("explicit-vr"))
                .create());
        group.addOption(OptionBuilder
                .withLongOpt("big-endian")
                .withDescription(rb.getString("big-endian"))
                .create());
        group.addOption(OptionBuilder
                .withLongOpt("implicit-vr")
                .withDescription(rb.getString("implicit-vr"))
                .create());
       opts.addOptionGroup(group);
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        try {
            CommandLine cl = parseComandLine(args);
            Main main = new Main();
            CLIUtils.configureConnect(main.remote, main.rq, cl);
            CLIUtils.configureBind(main.conn, main.ae, cl);
            CLIUtils.configure(main.conn, main.ae, cl);
            main.remote.setTLSProtocol(main.conn.getTLSProtocols());
            main.remote.setTLSCipherSuite(main.conn.getTLSCipherSuite());
            configureKeys(main, cl);
            main.setSOPClass(sopClassOf(cl));
            main.setTransferSyntaxes(tssOf(cl));
            main.setPriority(CLIUtils.priorityOf(cl));
            main.setDestination(destinationOf(cl));
            configureExtendedNegotiation(main, cl);
            ExecutorService executorService =
                    Executors.newSingleThreadExecutor();
            ScheduledExecutorService scheduledExecutorService =
                    Executors.newSingleThreadScheduledExecutor();
            main.setExecutor(executorService);
            main.setScheduledExecutorService(scheduledExecutorService);
            try {
                main.open();
                List<String> argList = cl.getArgList();
                if (argList.isEmpty())
                    main.retrieve();
                else
                    for (String arg : argList)
                        main.retrieve(new File(arg));
            } finally {
                main.close();
                executorService.shutdown();
                scheduledExecutorService.shutdown();
            }
       } catch (ParseException e) {
            System.err.println("movescu: " + e.getMessage());
            System.err.println(rb.getString("try"));
            System.exit(2);
        } catch (Exception e) {
            System.err.println("movescu: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static String destinationOf(CommandLine cl) throws ParseException {
        if (cl.hasOption("dest"))
            return cl.getOptionValue("dest");
        throw new ParseException(rb.getString("missing-dest"));
    }

    private static void configureExtendedNegotiation(Main main, CommandLine cl) {
        main.setRelationalQueries(cl.hasOption("relational"));
    }

    private static void configureKeys(Main main, CommandLine cl) {
        if (cl.hasOption("m")) {
            String[] keys = cl.getOptionValues("m");
            for (int i = 1; i < keys.length; i++, i++)
                main.addKey(CLIUtils.toTag(keys[i - 1]), keys[i]);
        }
        if (cl.hasOption("L"))
            main.addKey(Tag.QueryRetrieveLevel, cl.getOptionValue("L"));
    }

    private static SOPClass sopClassOf(CommandLine cl) throws ParseException {
        if (cl.hasOption("P")) {
            requiresQueryLevel("P", cl);
            return SOPClass.PatientRoot;
        }
        if (cl.hasOption("S")) {
            requiresQueryLevel("S", cl);
            return SOPClass.StudyRoot;
        }
        if (cl.hasOption("O")) {
            requiresQueryLevel("O", cl);
            return SOPClass.PatientStudyOnly;
        }
        if (cl.hasOption("I")) {
            requiresQueryLevel("I", cl);
            return SOPClass.CompositeInstanceRoot;
        }
        if (cl.hasOption("H")) {
            noQueryLevel("H", cl);
            return SOPClass.HangingProtocol;
        }
        if (cl.hasOption("C")) {
            noQueryLevel("C", cl);
            return SOPClass.ColorPalette;
        }
        throw new ParseException(rb.getString("missing"));
    }

    private static void requiresQueryLevel(String opt, CommandLine cl)
            throws ParseException {
        if (!cl.hasOption("L"))
            throw new ParseException(
                    MessageFormat.format(rb.getString("missing-level"), opt));
    }

    private static void noQueryLevel(String opt, CommandLine cl)
            throws ParseException {
        if (cl.hasOption("L"))
            throw new ParseException(
                    MessageFormat.format(rb.getString("invalid-level"), opt));
    }

    private static String[] tssOf(CommandLine cl) {
        if (cl.hasOption("explicit-vr"))
            return EVR_LE_FIRST;
        if (cl.hasOption("big-endian"))
            return EVR_BE_FIRST;
        if (cl.hasOption("implicit-vr"))
            return IVR_LE_ONLY;
        return IVR_LE_FIRST;
    }

    public void open() throws IOException, InterruptedException {
        rq.addPresentationContext(
                new PresentationContext(1, sopClass.cuid, tss));
        if (extNeg > sopClass.defExtNeg)
            rq.addExtendedNegotiation(
                    new ExtendedNegotiation(sopClass.cuid,
                            toInfo(extNeg | sopClass.defExtNeg)));
        as = ae.connect(conn, remote, rq);
    }

    private static byte[] toInfo(int extNeg) {
        if (extNeg == 1)
            return new byte[] { 1 };
        byte[] info = new byte[(extNeg & 8) == 0 ? 3 : 4];
        for (int i = 0; i < info.length; i++, extNeg >>>= 1)
            info[i] = (byte) (extNeg & 1);
        return info;
    }

    public void close() throws IOException, InterruptedException {
        if (as != null && as.isReadyForDataTransfer()) {
            as.waitForOutstandingRSP();
            as.release();
        }
    }

    public void retrieve(File f) throws IOException, InterruptedException {
        Attributes attrs;
        DicomInputStream dis = null;
        try {
            attrs = new DicomInputStream(f).readDataset(-1, -1);
        } finally {
            SafeClose.close(dis);
        }
        attrs.addAll(keys);
        retrieve(attrs);
    }

   public void retrieve() throws IOException, InterruptedException {
        retrieve(keys);
    }

    private void retrieve(Attributes keys) throws IOException, InterruptedException {
         DimseRSPHandler rspHandler = new DimseRSPHandler(as.nextMessageID()) {

            @Override
            public void onDimseRSP(Association as, Attributes cmd,
                    Attributes data) {
                super.onDimseRSP(as, cmd, data);
            }
        };

        as.cmove(sopClass.cuid, priority, keys, null, destination, rspHandler);
    }

}