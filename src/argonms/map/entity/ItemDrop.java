/*
 * ArgonMS MapleStory server emulator written in Java
 * Copyright (C) 2011  GoldenKevin
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package argonms.map.entity;

import java.awt.Point;

import argonms.character.inventory.InventorySlot;
import argonms.map.MapEntity;
import argonms.net.external.CommonPackets;

/**
 *
 * @author GoldenKevin
 */
public class ItemDrop extends MapEntity {
	public static final byte
		ITEM = 0,
		MESOS = 1
	;

	public static final byte
		PICKUP_ALLOW_OWNER = 0, //give charid for owner
		PICKUP_ALLOW_PARTY = 1, //give partyid for owner
		PICKUP_ALLOW_ALL = 2, //no owner
		PICKUP_EXPLOSION = 3 //give charid for owner i guess
	;

	public static final byte
		SPAWN_ANIMATION_DROP = 1,
		SPAWN_ANIMATION_NONE = 2,
		SPAWN_ANIMATION_FADE = 3
	;

	public static final byte
		DESTROY_ANIMATION_FADE = 0,
		DESTROY_ANIMATION_NONE = 1,
		DESTROY_ANIMATION_LOOTED = 2,
		DESTROY_ANIMATION_EXPLODE = 4
	;

	private byte mod;
	private byte dropType;
	private int id;
	private int owner;
	private Point dropFrom;
	private int dropper;
	private InventorySlot item;
	private boolean gone;
	private byte petLooter;

	public ItemDrop(InventorySlot item) {
		this.dropType = ITEM;
		this.id = item.getItemId();
		this.item = item;
	}

	public ItemDrop(int amt) {
		this.dropType = MESOS;
		this.id = amt;
	}

	public void init(int owner, Point dropTo, Point dropFrom, int dropper, byte allow) {
		this.owner = owner;
		this.setPosition(dropTo);
		this.dropFrom = dropFrom;
		this.dropper = dropper;
		this.mod = allow;
	}

	public byte getPetSlot() {
		return petLooter;
	}

	public byte getDropType() {
		return dropType;
	}

	public int getItemId() {
		return id;
	}

	public int getMesoValue() {
		return id;
	}

	public int getOwner() {
		return owner;
	}

	public Point getSourcePos() {
		return dropFrom;
	}

	public int getSourceEntityId() {
		return dropper;
	}

	public long getItemExpire() {
		return item.getExpiration();
	}

	public MapEntityType getEntityType() {
		return MapEntityType.ITEM;
	}

	public InventorySlot getItem() {
		return item;
	}

	public void pickUp(int looter) {
		this.owner = looter;
		this.gone = true;
		this.mod = DESTROY_ANIMATION_LOOTED;
	}

	public void pickUp(int looter, byte pet) {
		this.petLooter = pet;
		pickUp(looter);
	}

	public void explode() {
		this.gone = true;
		this.mod = DESTROY_ANIMATION_EXPLODE;
	}

	public void expire() {
		this.gone = true;
		this.mod = DESTROY_ANIMATION_FADE;
	}

	public boolean isAlive() {
		return !gone;
	}

	public boolean isVisible() {
		return !gone;
	}

	public byte[] getCreationMessage() {
		return CommonPackets.writeShowItemDrop(this, SPAWN_ANIMATION_DROP, mod);
	}

	public byte[] getShowEntityMessage() {
		return CommonPackets.writeShowItemDrop(this, SPAWN_ANIMATION_NONE, mod);
	}

	public byte[] getDisappearMessage() {
		return CommonPackets.writeShowItemDrop(this, SPAWN_ANIMATION_FADE, mod);
	}

	public byte[] getOutOfViewMessage() {
		return CommonPackets.writeRemoveItemDrop(this, DESTROY_ANIMATION_NONE);
	}

	public byte[] getDestructionMessage() {
		return CommonPackets.writeRemoveItemDrop(this, mod);
	}

	public boolean isNonRangedType() {
		return false;
	}
}
