package org.javaup.exception;


import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.enums.BaseCode;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 业务异常
 * @author: 阿星不是程序员
 **/
@EqualsAndHashCode(callSuper = true)
@Data
public class DpFrameException extends BaseException {
	
	private Integer code;
	
	private String message;

	public DpFrameException() {
		super();
	}

	public DpFrameException(String message) {
		super(message);
	}
	
	public DpFrameException(Integer code, String message) {
		super(message);
		this.code = code;
		this.message = message;
	}
	
	public DpFrameException(BaseCode baseCode) {
		super(baseCode.getMsg());
		this.code = baseCode.getCode();
		this.message = baseCode.getMsg();
	}

	public DpFrameException(Throwable cause) {
		super(cause);
	}

	public DpFrameException(String message, Throwable cause) {
		super(message, cause);
		this.message = message;
	}
}
