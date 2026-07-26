/*
 * Copyright (C) 2026 francitoshi@gmail.com
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE file in the project root for full license text.
 */
package io.francitoshi.docuflate;

import io.nut.base.jar.ManifestReader;
import io.nut.base.options.BooleanOption;
import io.nut.base.options.OptionParser;
import io.nut.base.os.Shell;
import io.nut.base.resources.ResourceBundles;
import io.nut.base.time.JavaTime;
import io.nut.base.util.Java;
import io.nut.base.util.Utils;
import java.io.File;
import java.time.LocalDate;
import java.util.Locale;
import java.util.ResourceBundle;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

public class Main
{

    private static final String DOCUFLATE = "docuflate";
    private static final String DATE;
    private static final String VER;
    private static final String LICENSE;
    private static final String HELP;
    private static final String VERSION;

    static
    {
        ResourceBundle bundle = ResourceBundles.getBundle(Main.class, Locale.getDefault());
        ManifestReader reader = new ManifestReader(Main.class);

        VER = Utils.firstNonNull(Main.class.getPackage().getImplementationVersion(), "[dev]");
        DATE = Utils.firstNonNull(reader.getMainAttribute(ManifestReader.BUILD_DATE), LocalDate.now().format(JavaTime.YYYY_MM_DD));
        HELP = getResourceText(bundle, "help");
        LICENSE = getResourceText(bundle, "license");
        VERSION = getResourceText(bundle, "version");
    }

    public static String getResourceText(ResourceBundle bundle, String key)
    {
        String fileName = bundle.getString(key);
        return ResourceBundles.getResourceAsString(Main.class, fileName).replace("{$VERSION}", VER).replace("{$DATE}", DATE);
    }

    public static void main(String... args)
    {
        OptionParser options = new OptionParser();

        BooleanOption onlyTxtOp = options.add(new BooleanOption('t', "only-txt"));
        BooleanOption onlyPdfOp = options.add(new BooleanOption('p', "only-pdf"));

        BooleanOption helpOp = options.add(new BooleanOption('h', "help"));
        BooleanOption licenseOp = options.add(new BooleanOption('L', "license"));
        BooleanOption versionOp = options.add(new BooleanOption('v', "version"));
        BooleanOption expandEnvOp = options.add(new BooleanOption('x', "expand-env"));

        try
        {
            args = options.parse(args);

            if (helpOp.isUsed())
            {
                System.out.println(HELP);
                return;
            }
            if (versionOp.isUsed())
            {
                System.out.println(VERSION);
                return;
            }
            if (licenseOp.isUsed())
            {
                System.out.println(LICENSE);
                return;
            }

            if(onlyTxtOp.isUsed() && onlyPdfOp.isUsed())
            {
                System.out.println("only-txt and only-pdf are incompatible");
                return;
            }
            if (args.length == 0)
            {
                System.out.println(HELP);
                return;
            }
            if(expandEnvOp.isUsed())
            {
                for(int i=0;i<args.length;i++)
                {
                    args[i] = Shell.expandShellVariables(args[i]);
                }
            }

            File docuflateDir = new File(Java.USER_HOME, ".docuflate");
            
            docuflateDir.mkdirs();
            
            File docuflate_mv = new File(docuflateDir, "docuflate.mv");
            
            boolean compact = docuflate_mv.exists() && docuflate_mv.length()>1000_000;
            
                
            try(Densifier densifier = new Densifier(docuflate_mv, onlyTxtOp.isUsed(), onlyPdfOp.isUsed(), args))
            {
                densifier.run();
            }

        }
        catch (Exception ex)
        {
            System.getLogger(Main.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }
    
}
