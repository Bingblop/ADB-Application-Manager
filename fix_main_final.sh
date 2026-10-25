#!/bin/bash
sed -i 's/File\[\] kids = f.listFiles();/String[] kids = f.list();/g' src/com/bloatware/bingblop/MainActivity.java
sed -i 's/if (kids != null) for (File k : kids) deleteRecursively(k);/if (kids != null) for (String k : kids) deleteRecursively(new File(f, k));/g' src/com/bloatware/bingblop/MainActivity.java
sed -i 's/if (folder && f.isDirectory()) { File\[\] kids = f.listFiles(); if (kids != null) for (File k : kids) k.delete(); }/if (folder \&\& f.isDirectory()) { \/* ⚡ Bolt: minimize GC pressure *\/ String\[\] kids = f.list(); if (kids != null) for (String k : kids) new File(f, k).delete(); }/g' src/com/bloatware/bingblop/MainActivity.java
sed -i 's/java.util.Arrays.sort(kids, new java.util.Comparator<File>() { @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); } });/java.util.Arrays.sort(kids);/g' src/com/bloatware/bingblop/MainActivity.java
sed -i 's/for (File k : kids) total += czCollect(out, k, name + "\/" + k.getName(), seen, problems, fileCount);/for (String k : kids) total += czCollect(out, new File(f, k), name + "\/" + k, seen, problems, fileCount);/g' src/com/bloatware/bingblop/MainActivity.java
