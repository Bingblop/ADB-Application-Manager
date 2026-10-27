#!/bin/bash
sed -i 's/File\[\] kids = f.listFiles();/String[] kids = f.list();/g' src/com/bloatware/bingblop/MainActivity.java
sed -i 's/if (kids != null) for (File k : kids) deleteRecursively(k);/if (kids != null) for (String k : kids) deleteRecursively(new File(f, k));/g' src/com/bloatware/bingblop/MainActivity.java
