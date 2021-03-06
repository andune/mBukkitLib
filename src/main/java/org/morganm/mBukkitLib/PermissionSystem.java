/*******************************************************************************
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 * 
 * Copyright (c) 2012 Mark Morgan.
 * All rights reserved.
 * 
 * Contributors:
 *     Mark Morgan - initial API and implementation
 ******************************************************************************/
package org.morganm.mBukkitLib;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import net.milkbowl.vault.permission.Permission;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import com.sk89q.wepif.PermissionsResolverManager;

/** Permission abstraction class, use Vault, WEPIF, Perm2 or superperms, depending on what's available.
 * 
 * Dependencies: (handled through maven automatically now)
 *   Vault 1.x: http://dev.bukkit.org/server-mods/vault/
 *   WorldEdit 5.x: http://build.sk89q.com/
 *   PermissionsEx: http://goo.gl/jthCz
 *   Permissions 2.7 or 3.x: http://goo.gl/liHFt (2.7) or http://goo.gl/rn4LP (3.x) 
 *   
 * Author's note: The "ideal" design would be to setup an interface class and let each permission
 *   type implement that interface and use polymorphism. In fact, that's how Vault and WEPIF work.
 *   However, the design goal for this class is to have a single class I can use between projects
 *   that implements permissions abstraction, thus the less-than-great C-style integer values,
 *   switch statements and if/else ladders.
 *   
 *   Now that I'm moving this into its own library, it should be refactored.
 * 
 * @author morganm
 *
 */
public class PermissionSystem {
	public enum Type {
		SUPERPERMS,
		VAULT,
		WEPIF,
		OPS
	}

	/** For use by pure superperms systems that have no notion of group, the
	 * convention is that groups are permissions that start with "group."
	 */
    private static final String GROUP_PREFIX = "group.";

	private final Plugin plugin;
	private final Logger log;
	private Type systemInUse;
	
    private net.milkbowl.vault.permission.Permission vaultPermission = null;
    private PermissionsResolverManager wepifPerms = null;
    
	public PermissionSystem(Plugin plugin, Logger log) {
		this.plugin = plugin;
		this.log = log;
	}
	
	public Type getSystemInUse() { return systemInUse; }
	
	public String getSystemInUseString() {
		switch(systemInUse) {
		case VAULT:
	        final String permName = Bukkit.getServer().getServicesManager().getRegistration(Permission.class).getProvider().getName();
			return "VAULT:"+permName;
		case WEPIF:
			String wepifPermInUse = "";
			try {
				Class<?> clazz = wepifPerms.getClass();
				Field field = clazz.getDeclaredField("permissionResolver");
				field.setAccessible(true);
				Object o = field.get(wepifPerms);
				String className = o.getClass().getSimpleName();
				wepifPermInUse = ":"+className.replace("Resolver", "");
			}
			// catch both normal and runtime exceptions
			catch(Throwable t) {
				// we don't care, it's just extra information if we can get it
			}
			
			return "WEPIF" + wepifPermInUse;
		case OPS:
			return "OPS";

		case SUPERPERMS:
		default:
			return "SUPERPERMS";
		
		}
	}
	
	public void setupPermissions() {
		setupPermissions(true);
	}
	public void setupPermissions(final boolean verbose) {
		List<String> permPrefs = null;
		if( plugin.getConfig().get("permissions") != null ) {
			permPrefs = plugin.getConfig().getStringList("permissions");
		}
		else {
			permPrefs = new ArrayList<String>(5);
			permPrefs.add("vault");
			permPrefs.add("wepif");
			permPrefs.add("pex");
			permPrefs.add("perm2-compat");
			permPrefs.add("superperms");
			permPrefs.add("ops");
		}
		
		for(String system : permPrefs) {
			if( "vault".equalsIgnoreCase(system) ) {
				if( setupVaultPermissions() ) {
					systemInUse = Type.VAULT;
//					if( verbose )
//						log.info(logPrefix+"using Vault permissions");
					break;
				}
			}
			else if( "wepif".equalsIgnoreCase(system) ) {
				if( setupWEPIFPermissions() ) {
					systemInUse = Type.WEPIF;
//					if( verbose )
//						log.info(logPrefix+"using WEPIF permissions");
					break;
				}
			}
			else if( "superperms".equalsIgnoreCase(system) ) {
				systemInUse = Type.SUPERPERMS;
//				if( verbose )
//					log.info(logPrefix+"using Superperms permissions");
	        	break;
			}
			else if( "ops".equalsIgnoreCase(system) ) {
				systemInUse = Type.OPS;
//				if( verbose )
//					log.info(logPrefix+"using basic Op check for permissions");
	        	break;
			}
		}
		
		if( verbose )
			log.info("using "+getSystemInUseString()+" for permissions");
	}
	
    /** Check to see if player has a given permission.
     * 
     * @param p The player
     * @param permission the permission to be checked
     * @return true if the player has the permission, false if not
     */
    public boolean has(CommandSender sender, String permission) {
    	Player p = null;
    	// console always has access
    	if( sender instanceof ConsoleCommandSender )
    		return true;
    	if( sender instanceof Player )
    		p = (Player) sender;
    	
    	if( p == null )
    		return false;
    	
    	boolean permAllowed = false;
    	switch(systemInUse) {
    	case VAULT:
    		permAllowed = vaultPermission.has(p, permission);
    		break;
    	case WEPIF:
    		permAllowed = wepifPerms.hasPermission(p.getName(), permission);
    		break;
    	case SUPERPERMS:
    		permAllowed = p.hasPermission(permission);
    		break;
    	case OPS:
    		permAllowed = p.isOp();
    		break;
    	}
    	
    	return permAllowed;
    }
    
    public boolean has(String world, String player, String permission) {
    	boolean permAllowed = false;
    	switch(systemInUse) {
    	case VAULT:
    		permAllowed = vaultPermission.has(world, player, permission);
    		break;
    	case WEPIF:
    		permAllowed = wepifPerms.hasPermission(player, permission);
    		break;
    	case SUPERPERMS:
    	{
    		Player p = plugin.getServer().getPlayer(player);
			// technically this is not guaranteed to be accurate since superperms
			// doesn't support checking cross-world perms. Upgrade to a better
			// perm system if you care about this.
    		if( p != null )
    			permAllowed = p.hasPermission(permission);
    		break;
    	}
    	case OPS:
		{
    		Player p = plugin.getServer().getPlayer(player);
    		if( p != null )
    			permAllowed = p.isOp();
    		break;
    	}
    	}

    	return permAllowed;
    }
    public boolean has(String player, String permission) {
    	return has("world", player, permission);
    }
    
	public String getPlayerGroup(String world, String playerName) {
    	String group = null;
    	
    	switch(systemInUse) {
    	case VAULT:
    		group = vaultPermission.getPrimaryGroup(world, playerName);
    		break;
    	case WEPIF:
    	{
    		String[] groups = wepifPerms.getGroups(playerName);
    		if( groups != null && groups.length > 0 )
    			group = groups[0];
    		break;
    	}
    	
    	case SUPERPERMS:
    	{
    		Player player = plugin.getServer().getPlayer(playerName);
    		group = getSuperpermsGroup(player);
    		break;
    	}
            
        // OPS has no group support
    	case OPS:
    		break;
    	}
    	
    	return group;
    }
	
	/** Superperms has no group support, but we fake it (this is slow and stupid since
	  * it has to iterate through ALL permissions a player has).  But if you're
	  * attached to superperms and not using a nice plugin like bPerms and Vault
	  * then this is as good as it gets.
	  * 
	  * @param player
	  * @return the group name or null
	  */
	private String getSuperpermsGroup(Player player) {
		if( player == null )
			return null;
		
		String group = null;
		
		// this code shamelessly adapted from WorldEdit's WEPIF support for superperms
        Permissible perms = getPermissible(player);
        if (perms != null) {
            for (PermissionAttachmentInfo permAttach : perms.getEffectivePermissions()) {
                String perm = permAttach.getPermission();
                if (!(perm.startsWith(GROUP_PREFIX) && permAttach.getValue())) {
                    continue;
                }
                
                // we just grab the first "group.XXX" permission we can find
                group = perm.substring(GROUP_PREFIX.length(), perm.length());
                break;
            }
        }
        
        return group;
	}

	/** This code shamelessly stolen from WEPIF in order to support a fake "group"
	 * notion for Superperms.
	 * 
	 * @param offline
	 * @return
	 */
    private Permissible getPermissible(OfflinePlayer offline) {
        if (offline == null) return null;
        Permissible perm = null;
        if (offline instanceof Permissible) {
            perm = (Permissible) offline;
        } else {
            Player player = offline.getPlayer();
            if (player != null) perm = player;
        }
        return perm;
    }

    private boolean setupVaultPermissions()
    {
    	Plugin vault = plugin.getServer().getPluginManager().getPlugin("Vault");
    	if( vault != null ) {
	        RegisteredServiceProvider<net.milkbowl.vault.permission.Permission> permissionProvider = plugin.getServer().getServicesManager().getRegistration(net.milkbowl.vault.permission.Permission.class);
	        if (permissionProvider != null) {
	            vaultPermission = permissionProvider.getProvider();
	        }
    	}
    	
        return (vaultPermission != null);
    }
    
    private boolean setupWEPIFPermissions() {
    	Plugin worldEdit = plugin.getServer().getPluginManager().getPlugin("WorldEdit");
    	if( worldEdit != null ) {
    		PermissionsResolverManager.initialize(plugin);
    		wepifPerms = PermissionsResolverManager.getInstance();
    	}

    	return wepifPerms != null;
    }
}
