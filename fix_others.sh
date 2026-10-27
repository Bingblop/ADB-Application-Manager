#!/bin/bash
sed -i 's/File\[\] old = dir.listFiles();/String[] old = dir.list();/g' src/com/bloatware/bingblop/MainActivity.java
sed -i 's/if (old != null) for (File f : old)/if (old != null) for (String f_name : old)/g' src/com/bloatware/bingblop/MainActivity.java
sed -i 's/if (old != null) for (File o : old) o.delete();/if (old != null) for (String o : old) new File(dir, o).delete();/g' src/com/bloatware/bingblop/MainActivity.java
sed -i 's/File\[\] kids = dir.listFiles();/String[] kids = dir.list();/g' src/com/bloatware/bingblop/MainActivity.java
sed -i 's/for (File k : kids) {/for (String k_name : kids) { File k = new File(dir, k_name);/g' src/com/bloatware/bingblop/MainActivity.java
sed -i 's/File\[\] vols = new File("\/storage").listFiles();/String[] vols = new File("\/storage").list();/g' src/com/bloatware/bingblop/MainActivity.java
