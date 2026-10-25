#!/bin/bash
sed -i 's/File\[\] kids = f.listFiles();/String[] kids = f.list();/g' src/com/bloatware/bingblop/MainActivity.java
sed -i 's/if (kids != null) for (File k : kids) deleteRecursively(k);/if (kids != null) for (String k : kids) deleteRecursively(new File(f, k));/g' src/com/bloatware/bingblop/MainActivity.java
sed -i 's/if (folder && f.isDirectory()) { File\[\] kids = f.listFiles(); if (kids != null) for (File k : kids) k.delete(); }/if (folder \&\& f.isDirectory()) { String\[\] kids = f.list(); if (kids != null) for (String k : kids) new File(f, k).delete(); }/g' src/com/bloatware/bingblop/MainActivity.java
