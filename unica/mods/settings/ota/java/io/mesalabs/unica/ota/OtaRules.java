/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.ota;
final class OtaRules {
 static void compatible(String device,String installed,String base,String target) throws java.io.IOException {
  if(!"dm3q".equals(device))throw new java.io.IOException("OTA installation is supported only on dm3q");
  if(!base.matches("[0-9]+")||!target.matches("[0-9]+")||Long.parseLong(target)<=Long.parseLong(base))throw new java.io.IOException("Invalid update release identity");
  if(installed.equals(target))throw new java.io.IOException("This update is already applied");
  if(!installed.equals(base))throw new java.io.IOException("This update requires a different base ROM. Install the full ROM instead.");
 }
}
