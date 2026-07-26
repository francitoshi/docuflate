/*
 * Copyright (C) 2026 francitoshi@gmail.com
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE file in the project root for full license text.
 */
package io.francitoshi.docuflate;

import io.nut.base.io.PathWalker;
import io.nut.base.io.PathWalker.Option;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

/**
 * ShrinkPdf
 *
 * Uso: java ShrinkPdf archivo1.pdf archivo2.pdf ...
 *
 * Para cada archivo pasado como parámetro: 1. Intenta comprimirlo con qpdf. Si
 * el resultado es más pequeño, reemplaza el original. 2. Comprime el resultado
 * con gzip, bzip2, xz y zstd. 3. Se queda únicamente con el archivo más pequeño
 * de todos (incluyendo la posibilidad de que el propio original -sin comprimir
 * con ninguna herramienta- sea el ganador). 4. Informa del % de mejora
 * conseguido en cada operación, con 2 decimales.
 *
 * Requiere que las herramientas qpdf, gzip, bzip2, xz y zstd estén instaladas y
 * disponibles en el PATH del sistema.
 */
public class Densifier implements Runnable, AutoCloseable
{    
    public static record FileInfo(long lastModified, long size) implements Serializable {}

    final boolean onlyTxt;
    final boolean onlyPdf;

    final HashSet<Path> paths = new HashSet<Path>();
    final File cache;
    final MVStore store;
    final MVMap<String, FileInfo> map;
    
    public Densifier(File cache, boolean onlyTxt, boolean onlyPdf, String... paths)
    {
        this.cache = cache;
        this.store = new MVStore.Builder().fileName(cache.getAbsolutePath()).compress().open();
        this.map = this.store.openMap("cache");
        this.onlyTxt = onlyTxt;
        this.onlyPdf = onlyPdf;
        for (String s : paths)
        {
            this.paths.add(Paths.get(s));
        }
    }
 
    @Override
    public void close() throws Exception
    {
        this.store.commit();
        this.store.close();
    }

    private final EnumSet<Option> options = EnumSet.of(Option.OnlyFiles);
    private final PathWalker walker = new PathWalker(options, Short.MAX_VALUE)
    {
        @Override
        protected void process(Path path, BasicFileAttributes attrs) throws IOException
        {
            accept(path, attrs);
        }
    };

    private volatile long bytesAcum = 0;

    @Override
    public void run()
    {
        try
        {
            if(cache.exists() && cache.length()>1000_000)
            {
                map.keySet().removeIf(file -> new File(file).exists());
            }
            
            walker.walk(paths);
            System.out.printf("\nimprovement %d bytes\n", bytesAcum);
        }
        catch (IOException ex)
        {
            System.getLogger(Densifier.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }
    }
    
    public void accept(Path path, BasicFileAttributes attrs) throws IOException
    {
        if (!Files.exists(path) || !Files.isRegularFile(path))
        {
            System.err.println("Warning: '" + path + "' not a regular file. Omitted.");
            return;
        }
        boolean txt = path.toString().toLowerCase().endsWith(".txt");
        boolean pdf = path.toString().toLowerCase().endsWith(".pdf");
        
        if(!txt && !pdf)
        {
            return;
        }
        if(txt && onlyPdf)
        {
            return;
        }
        if(pdf && onlyTxt)
        {
            return;
        }

        //probably not modified
        String key = path.toAbsolutePath().toString();
        FileInfo value = map.get(key);
        if(value!=null && value.size==attrs.size() && value.lastModified==attrs.lastModifiedTime().toMillis())
        {
            System.out.println("== ✘ " + path + " ==");
            return;
        }
        
        long size0 = Files.size(path);
        System.out.println("== " + path + " (" + size0 + " bytes) ==");

        // ---------- PASO 1: qpdf ----------
        Path qpdfTmp = Paths.get(path.toString() + ".qpdf.tmp");
        boolean qpdfOk = qpdf(path, qpdfTmp);
        
        long size = size0;
        
        if (qpdfOk && Files.exists(qpdfTmp))
        {
            size = Files.size(qpdfTmp);
            double pct = improvement(size0, size);
            if (size < size0)
            {
                Files.move(qpdfTmp, path, StandardCopyOption.REPLACE_EXISTING);
                System.out.println(String.format(Locale.US, "  [qpdf]  %8d -> %8d bytes | opt: %.2f%% ✔", size0, size, pct));
            }
            else
            {
                Files.deleteIfExists(qpdfTmp);
                System.out.println(String.format(Locale.US,"  [qpdf]  %8d -> %8d bytes | opt: %.2f%% ✘", size0, size, pct));
            }
        }
        else
        {
            System.out.println("  [qpdf]   unavailable or failed. The file remains unchanged..");
        }

        size = Math.min(size0,size);

        // ---------- PASO 2: gzip, bzip2, xz ----------
        Map<String, Path> candidates = new LinkedHashMap<>();
        candidates.put("gzip", gzip(path));
        candidates.put("bzip2", bzip2(path));
        candidates.put("xz", xz(path));

        String bestLabel = "original (uncompressed)";
        Path bestPath = path;
        long minSize = size;

        for (Map.Entry<String, Path> e : candidates.entrySet())
        {
            String type = e.getKey();
            Path name = e.getValue();

            if (name == null || !Files.exists(name))
            {
                System.out.println("  [" + type + "]  unavailable or failed.");
                continue;
            }

            long curSize = Files.size(name);
            double pct = improvement(size, curSize);
            System.out.println(String.format(Locale.US, "  %-5s %8d -> %8d bytes = %.2f%%", '['+type+']', size, curSize, pct));

            if (curSize < minSize)
            {
                minSize = curSize;
                bestPath = name;
                bestLabel = type;
            }
        }

        // ---------- Clean up: keep only the smallest file ----------
        for (Path c : candidates.values())
        {
            if (c != null && Files.exists(c) && !c.equals(bestPath))
            {
                Files.deleteIfExists(c);
            }
        }

        if (!bestPath.equals(path) && Files.exists(path))
        {
            // An external compressor has won: we eliminated the intermediate .pdf (post-qpdf)
            Files.deleteIfExists(path);
            if (value != null)
            {
                map.remove(key);
            }
        }
        else
        {
            map.put(key, new FileInfo(attrs.lastModifiedTime().toMillis(), attrs.size()));
        }

        double pctTotal = improvement(size0, minSize);
        System.out.println(String.format(Locale.US, "  => [%s]: (%d bytes) %.2f%%", bestLabel, minSize, pctTotal));
        bytesAcum += (size0-minSize);
    }

    private static double improvement(long antes, long despues)
    {
        if (antes == 0)
        {
            return 0.0;
        }
        return (antes - despues) * 100.0 / antes;
    }

    private static int execute(String... comando) throws IOException, InterruptedException
    {
        ProcessBuilder pb = new ProcessBuilder(comando);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (InputStream is = p.getInputStream())
        {
            byte[] buffer = new byte[4096];
            while (is.read(buffer) != -1)
            {
                // The output is discarded; only the return code is of interest.
            }
        }
        return p.waitFor();
    }

    private static boolean qpdf(Path in, Path out)
    {
        try
        {
            int code = execute("qpdf",
                    "--compress-streams=y",
                    "--object-streams=generate",
                    "--recompress-flate",
                    "--compression-level=9",
                    in.toString(),
                    out.toString());
            return code == 0;
        }
        catch (IOException | InterruptedException e)
        {
            return false;
        }
    }

    private static Path gzip(Path file)
    {
        Path out = Paths.get(file.toString() + ".gz");
        try
        {
            Files.deleteIfExists(out);
            int code = execute("gzip", "-k", "-9", "-f", file.toString());
            return (code == 0 && Files.exists(out)) ? out : null;
        }
        catch (IOException | InterruptedException e)
        {
            return null;
        }
    }

    private static Path bzip2(Path file)
    {
        Path out = Paths.get(file.toString() + ".bz2");
        try
        {
            Files.deleteIfExists(out);
            int code = execute("bzip2", "-k", "-9", "-f", file.toString());
            return (code == 0 && Files.exists(out)) ? out : null;
        }
        catch (IOException | InterruptedException e)
        {
            return null;
        }
    }

    private static Path xz(Path file)
    {
        Path out = Paths.get(file.toString() + ".xz");
        try
        {
            Files.deleteIfExists(out);
            int code = execute("xz", "-k", "-9", "-f", file.toString());
            return (code == 0 && Files.exists(out)) ? out : null;
        }
        catch (IOException | InterruptedException e)
        {
            return null;
        }
    }
}
